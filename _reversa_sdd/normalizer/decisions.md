# ingestion/normalizer — решения (ADR-ссылки)

> `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`. ADR не копируются, только ссылки.

| Решение | Где проявлено в модуле | ADR / документ | Статус |
|---|---|---|---|
| Normalizer — единственное место с source DTO, наружу только `GraphCommand` | `NZ/CanonicalMapper.java:10-13` | [0002](../../docs/adr/0002-four-contours.md), `docs/03-interfaces.md` §5.2 | 🟢 |
| Идентичность записи — `(source, sourceType, sourceId)`; `SourceRecord.active=true` | `NZ/Normalizer.java:68-74` | [0003](../../docs/adr/0003-gid-and-source-record-identity.md) | 🟡 (ADR нормализатор не упоминает, связь выведена) |
| Мастер связей определяет authority matrix; связь утверждает запись события (`assertedBy = self`) | `NZ/CommandSink.java:63,90` | [0008](../../docs/adr/0008-authority-matrix-field-level.md) | 🟡 (ADR нормализатор не упоминает, связь выведена) |
| Нет автослияния; ссылки только по ключам и alias, имя не ключ | `NZ/ScmMapper.java:68-82`; `NT/EamArchObjectMapperTest#sameNameDifferentIdsAreDifferentKeysNotMerged` | [0019](../../docs/adr/0019-identity-resolution-no-auto-merge.md) | 🟡 (ADR нормализатор не упоминает, связь выведена) |
| Удаление = tombstone-событие, не нормализация | `NZ/Normalizer.java:55-57`; `NT/NormalizerTest#unsupportedEventTypeIsQuarantined` | [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) | 🟡 (ADR нормализатор не упоминает, связь выведена) |
| `Deployment` из deploy map; формат временный | `NZ/DeployMapMapper.java:23-26` | [0009](../../docs/adr/0009-deployment-from-deploymap.md) | 🟢, формат — 🟡 |
| `Namespace`/`KubernetesCluster` не наполняются (мапперов нет) | отсутствие маппера | [0011](../../docs/adr/0011-namespace-cluster-modeled-not-populated.md) | 🟢 |
| Ключ EAM — целочисленный `id`; формат id открыт | `NZ/Payload.java:109-122`; `EamMapper.java:181` | [0024](../../docs/adr/0024-open-source-id-format.md), `docs/06-eam-integration.md` | 🟢 |
| Структурные связи темпоральны, `validity` только из источника | `NZ/EamMapper.java:94,112,192`; `ScmMapper.java:115-117`; `DeployMapMapper.java:240-242` | [0028](../../docs/adr/0028-structural-relations-temporal.md) | 🟢 |
| Chart несёт систему, репозиторий и commit; привязка к сервису по репозиторию; `DEPLOYED_FROM{commit}` | `NZ/DeployMapMapper.java:142-225` | [0029](../../docs/adr/0029-helm-chart-system-repo-commit.md) | 🟢 |
| Система из chart не сравнивается в маппере (конфликт открывает `EventProcessor`) | `NT/DeployMapMapperTest#systemMismatchGivesOrdinaryBindingWithoutConflictOrWarning` | [0030](../../docs/adr/0030-deployment-system-conflict.md) | 🟢 |
| `platform_id` ИТ-системы хранится свойством, прямой связи «система→платформа» нет | `NZ/EamMapper.java:179-181` | [0031](../../docs/adr/0031-it-system-platform-invariant.md) | 🟢 |
| Критичность EAM через явную таблицу, неизвестное значение не угадывается (`criticalityRaw` + `UNMAPPED_CRITICALITY`) | `NZ/EamMapper.java:129-130,161-168` | `docs/06-eam-integration.md` (раздел «Нормализация и связи»), допущение 2 | 🟢 |
