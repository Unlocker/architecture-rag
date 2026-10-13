# 0029. Helm chart несёт систему, репозиторий и commit; развёртывание привязывается к репозиторию

- **Статус:** Принято
- **Дата:** 2026-10-10
- **Источник:** решения владельца 2026-10-10 в UNLOCKER-248 (фича), UNLOCKER-249, UNLOCKER-250, UNLOCKER-251; UNLOCKER-252
- **Кто принял:** владелец, по постановке архитектора

## Контекст

ADR [0009](0009-deployment-from-deploymap.md) брал `Deployment` из deploy map и Helm charts во временном формате `deploymap-poc-0`: сервис задавался строкой `service` из payload, а о том, из какого кода собран релиз, граф ничего не знал. Агенту нельзя было ответить на вопрос «какой commit сейчас в проде» и «какой репозиторий за этим развёртыванием». Владелец решил, что эти сведения живут в `Chart.yaml` релиза, а связь с сервисом строится по репозиторию.

## Решение

1. **Аннотации chart.** `Chart.yaml` несёт три аннотации (в payload — `chartMeta.annotations`): `archrag.io/eam-system-id`, `archrag.io/git-repo-url`, `archrag.io/git-commit`. Формат payload — `deploymap-poc-1`; `deploymap-poc-0` остаётся валидным и разбирается по-старому. Для `poc-1` нужны все три аннотации.
2. **Система в chart — целочисленный `id` EAM**, а не `eam_id` ([ADR 0024](0024-open-source-id-format.md)). `Deployment.eamSystemId` — положительное целое.
3. **Сервис находится по репозиторию.** URL из chart нормализуется (`GitUrls`) и ищется среди `Repository` SCM; сервис — тот, что `IMPLEMENTED_IN` этого репозитория. Порядок выбора: `service` из payload, если он среди сервисов репозитория; единственный сервис репозитория; `service` из payload как есть (отложенная связь); иначе связи нет и пишется предупреждение `DEPLOYMENT_SERVICE_UNRESOLVED`. `service` из deploy map остаётся запасным путём.
4. **Система служит проверкой, прямой связи `Deployment → ITSystem` нет.** `Deployment.eamSystemId` — целочисленный `id` EAM, тот же ключ, что у `ITSystem` после H.4a (UNLOCKER-255). Расхождение системы из chart с системой сервиса фиксируется как конфликт вида `RELATION`: см. [ADR 0030](0030-deployment-system-conflict.md) (UNLOCKER-254). Сам `DeployMapMapper` систему не сравнивает.
5. **Commit.** У `Deployment` свойства `sourceCommit` (40 hex в нижнем регистре), `repositoryUrl`, `eamSystemId`. Связь `DEPLOYED_FROM` (`Deployment → Repository`) темпоральная, авторитетный источник — DEPLOYMAP, свойство `commit` входит в identity ([ADR 0028](0028-structural-relations-temporal.md): `RelationType.identityProperties()`). Смена commit даёт новое ребро, а прежнее закрывается (`validTo`); физического удаления нет ([ADR 0020](0020-tombstone-instead-of-hard-delete.md)).
6. **Нормализация URL.** `GitUrls.normalize`: ssh/scp-форма и `https` приводятся к одному виду (в seed `git@git.example.org:pay/payments-api.git` → `https://git.example.org/pay/payments-api`). Сопоставление с `Repository.url` идёт по нормализованному значению.
7. **Карантин.** Нет аннотации — `MISSING_REQUIRED_FIELD`; неверный формат (commit не 40 hex, `id` не положительное целое, нераспознаваемый URL, неизвестный `format`) — `INVALID_PAYLOAD`. Причина называет аннотацию, но не значение: значения источника недоверенные. Неизвестный репозиторий не карантин: связь `DEPLOYED_FROM` откладывается на alias `(SCM, REPOSITORY_URL, url)` и достраивается проектором, когда SCM пришлёт репозиторий с этим URL.

Вне среза: сущность `Artifact` и связь `BUILDS`, рендеринг Helm (читаются только метаданные `Chart.yaml`).

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| A. Аннотации chart + привязка по репозиторию + темпоральная `DEPLOYED_FROM {commit}` | Один источник правды (chart), история смены commit, сервис не зависит от строки `service` | Нужен alias по URL для репозитория, которого ещё нет | Выбран |
| B. Оставить `service` из deploy map, commit — только свойство | Минимум изменений | Нет истории, привязка к сервису по строке без проверки | Отклонён: не отвечает на «какой код в проде» с историей |
| C. Прямая связь `Deployment → ITSystem` | Проще чтение | Два источника правды о системе, расхождение не видно | Отклонён: система служит проверкой через `Conflict` (п. 4) |
| D. `Artifact` и `BUILDS` | Полная цепочка сборки | Нет источника сборок в PoC | Вне среза |

## Последствия

- **Положительные:** `find_runtime_footprint` и консоль показывают commit и репозиторий развёртывания; смена commit видна как закрытое и открытое ребро; битые аннотации не попадают в граф, а уходят в карантин с кодом.
- **Отрицательные / ограничения:** при возврате на прежний commit (A→B→A) заново открывается исходное ребро `DEPLOYED_FROM {commit:A}` (`MERGE` по identity), поэтому история первого периода A теряется и ответ на «какой commit был в проде в момент T» не гарантируется; решение (identity `(from, to, commit, validFrom)`) вынесено в отдельный тикет после фичи H; проверка системы (п. 4) пока не работает; выбор сервиса — снимок на момент нормализации, при переносе репозитория к другому сервису связь пересчитывается только с новой версией записи или rebuild; формат `deploymap-poc-1` остаётся временным ([ADR 0009](0009-deployment-from-deploymap.md)).
- **Затронутый код:** `platform/source-stubs` (`DeployMapFormat`, `StubSources`), `contracts/canonical-model` (`Deployment`, `GitUrls`, `RelationType`, `AuthorityMatrix`, `LookupKeys`), `ingestion/normalizer` (`DeployMapMapper`), `ingestion/graph-projector` (identity-свойства связей, alias репозитория), `access/graph-query-core` (`RuntimeTemplates`, `AssetTemplates`, `DependencyTemplates`), `access/mcp-server` (`FindRuntimeFootprintResult`), `access/admin-ui` (карточка актива).

## Связанные ADR

[0009](0009-deployment-from-deploymap.md) — уточняет: формат `deploymap-poc-1` и привязка по репозиторию. [0008](0008-authority-matrix-field-level.md) — `DEPLOYED_FROM` в матрице авторитетности. [0010](0010-master-systems-are-stubs.md) — заглушки. [0024](0024-open-source-id-format.md) — `id` EAM. [0028](0028-structural-relations-temporal.md) — механизм темпоральных связей и identity-свойств.

## Дополнение 2026-10-13

Исходный текст выше не менялся. Дополнение описывает механизм отложенных связей, на который опирается п. 7; источник — UNLOCKER-278, кандидат К-3 в `_reversa_sdd/adrs/candidates.md` (UNLOCKER-269), согласие владельца в UNLOCKER-260 (2026-10-13). Кто принял: владелец, 2026-10-13.

1. **Механизм `PendingRelation`.** Нормализатор не создаёт фиктивных узлов: неизвестный конец связи превращается в `UnresolvedReference`, затем в `DeferRelation` (`ingestion/normalizer/.../CommandSink.java:13-22`, `:62-65`, `:94-97`). Проектор хранит такую связь узлом `PendingRelation`, ключ закреплён constraint `pending_relation_key` (`ingestion/graph-projector/.../schema/Neo4jSchema.java:52`; `PendingRelations.java:25`, `:75`). Связь достраивается в транзакции проекции конечной точки (`GraphProjector.java:367-373`), отложенные связи записи заменяются целиком при каждой применённой проекции (`GraphProjector.java:328-337`) и удаляются при tombstone.
2. **Alias-ключи.** Помимо `SourceKey(SCM, REPOSITORY_URL, url)` (п. 7 исходного текста) используется `SourceKey(EAM, IT_SYSTEM_CODE, <eamCode>)`: он достраивается записью EAM `ITSystem` с тем же `eamCode` (`GraphProjector.java:73-76`, `:384-412`; `contracts/canonical-model/.../provenance/LookupKeys.java:14`, `:29`). Ключ `ITSystem` в EAM — целочисленный `id`, а `eam_id` — свойство, поэтому на ключ `(EAM, IT_SYSTEM, <eam_id>)` ссылаться нельзя.
3. **Границы.** У отложенной связи нет срока годности; reconciliation её не лечит; повторной проверки после записи отложенной связи нет (`GraphProjector.java:64-67`). Допущение держится на одном экземпляре ingestion-сервиса и однопоточном диспетчере — [ADR 0036](0036-single-instance-journal-dispatcher.md); при втором экземпляре оно перестаёт выполняться.
4. **Отвергнутые альтернативы.** Stub-узел для неизвестного конца (`gid` чужого конца не создаётся); возврат `RETRYING` до прихода конца (код `UNRESOLVED_ENDPOINT`, `docs/02-data-model.md:273`); по данным кандидата К-3 так было до E1.14, в истории кода это не перепроверялось.
