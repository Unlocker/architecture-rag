# map-eam-objects — дизайн

> Общий дизайн: [`../design.md`](../design.md). `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`. Все номера строк — `EamMapper.java` (для `NZ/EamMapper.java`).

## Interface
`EamMapper implements CanonicalMapper`: `source() = EAM` (`:56-58`), `supportedSchemaVersions() = {"1"}` (`:61-63`), `map(Input, CommandSink)` (`:66-118`). Константы пакета: `IT_SYSTEM`, `PLATFORM`, `SOLUTION`, `TEAM`, `SERVICE_DEPENDENCY` (`:49-53`). Константа `ScmMapper.SERVICE` используется здесь для ключей сервисов (`:107,109`).

## Основной поток `map` (`:66-118`)
`switch (key.sourceType())`:
1. `PLATFORM`, `SOLUTION` → `mapArchObject` (`:70`).
2. `TEAM` → `UpsertNode(Team(p.required("name"), p.optional("type")|null))` (`:71`).
3. `IT_SYSTEM` (`:72-99`): если `p.optionalObject("attrs")` присутствует → `mapArchObject` и `return`. Иначе плоский: `UpsertNode(ITSystem(required name, optional status, optional criticality→Criticality.valueOf, optional description))` (`:77-84`); `ownerTeam` (если есть) → `sink.relation(OWNED_BY, key, IT_SYSTEM, (EAM,TEAM,team), TEAM, validity=ownerSince?, "ownerTeam")` (`:85-95`); непустой `optionalList("dependsOn")` → `sink.warn("DEPENDS_ON_NOT_SUPPORTED")` (`:96-98`).
4. `SERVICE_DEPENDENCY` (`:100-115`): `props` (`LinkedHashMap`) из `kind`, `protocol`, `criticality→criticality(v).name()`; `sink.link(DEPENDS_ON, (SCM,SERVICE,required from), SERVICE, (SCM,SERVICE,required to), SERVICE, props, validFrom?, "from", "to")`. Порядок: сначала собираются `props` (неверная `criticality` → `INVALID_PAYLOAD` раньше проверки `from`/`to`), затем аргументы `link` слева направо: `from` читается раньше `to`, поэтому при отсутствии обоих `reason` называет `from` 🟢.
5. `default` → `NormalizationException("UNKNOWN_SOURCE_TYPE", "unknown EAM sourceType")` (`:116`).

## `mapArchObject` (`:132-196`)
1. `attrs = new Payload(input.payload()).optionalObject("attrs").orElseThrow(MISSING_REQUIRED_FIELD attrs)` (`:134-137`).
2. `PLATFORM` (`:139-148`): `Platform(attrs.required("name"), optional type, version, lifecycle, description)`.
3. `SOLUTION` (`:149-159`): `Solution(required name, optional lifecycle, description, optionalIdRef("platform_id")→Long)`; затем `reference(attrs, "platform_id", USES_PLATFORM, key, SOLUTION, PLATFORM, PLATFORM, sink)`.
4. `default` (= `IT_SYSTEM`, `:160-194`):
   - `rawCriticality = attrs.optional("criticality")`; `criticality = rawCriticality.map(v -> CRITICALITY.get(v.toUpperCase(Locale.ROOT))).orElse(null)`; `unmapped = criticality == null ? rawCriticality.orElse(null) : null` (`:161-163`). `CRITICALITY = Map.of("HIGH","MEDIUM","LOW")` → `Criticality` (`:129-130`).
   - `unmapped != null` → `sink.warn("UNMAPPED_CRITICALITY")` + `LOG.log(DEBUG, …)` без значения (`:164-168`).
   - `UpsertNode(ITSystem(required name, status, criticality, description, eam_id, unmapped, optionalIdRef("platform_id")→Long))` (`:169-179`).
   - `reference(attrs, "product_id", USES_SOLUTION, key, IT_SYSTEM, SOLUTION, SOLUTION, sink)` (`:181`).
   - `ownerTeam` → `OWNED_BY` (`:182-193`), `validity` из `ownerSince`.
5. `reference(...)` (`:199-214`): `attrs.optionalIdRef(field).ifPresent(id -> sink.relation(type, from, fromLabel, (EAM, targetType, id), targetLabel, validity=null, field))`.

## Альтернативные потоки
- Любая ошибка типа поля/значения → `INVALID_PAYLOAD` (см. `Payload`); пустое `name` → `MISSING_REQUIRED_FIELD`.
- Нарушения модели (`platformId ≤ 0`) → `IllegalArgumentException` → `INVALID_PAYLOAD` (фикс. текст) 🟢.
- `legacyCriticality()` для плоского/`SERVICE_DEPENDENCY`: `Criticality.valueOf(upper)`, ошибка → `null`; `unmappedCriticality()` ставит warning `UNMAPPED_CRITICALITY` и возвращает сырое значение (`criticalityRaw` узла или свойство связи) (К-10, UNLOCKER-287).

## Зависимости
`Payload`, `CommandSink`, `ScmMapper.SERVICE` (константа), `canonical-model` (узлы `Platform/Solution/ITSystem/Team`, `RelationType`, `Validity`, `Criticality`). Правила и решения: `docs/06-eam-integration.md` (раздел «Нормализация и связи», «Решения» 2, 3, допущение 9), ADR 0031.

## Решения
| Решение | Evidence | Уверенность |
|---|---|---|
| Ключ — `id` объекта EAM без префикса | `docs/06-eam-integration.md` (раздел «Сопоставление ключей»), `NZ/EamMapper.java:181` | 🟢 |
| `eam_id` → `ITSystem.eamCode`, плоский IT_SYSTEM без `eamCode` | `:177`, `:32-33` | 🟢 |
| Критичность: словарь HIGH/MEDIUM/LOW, прочее в `criticalityRaw` | `:129-130,161-168` | 🟢 |
| `platform_id` системы — свойство, не связь | `:180` | 🟢 |
| `ownerTeam` — ссылка на `(EAM,TEAM,<строка>)` | `:92,190` | 🟢 (допущение 9 `docs/06`, 🟡 в проде: Q-14) |

## Связи с другими спецификациями
Матрица влияния: «`adapters/eam-adapter` (`EamApiConnector`)» → `EamMapper` (формат `ArchObject`, критичность, `attrs`) проверяется `EamArchObjectMapperTest`; `eam-api-stub`/`docs/eam-api.yaml`. См. [`../design.md`](../design.md) §6.

## Риски
- 🟢 [R.8: ответ владельца, вопрос 7] Q-02, Q-03, Q-05 (расхождения `docs/06` с кодом), Q-04 (`docs/03`).
- 🟡 Q-07 (две таблицы критичности), Q-08 (`attrs: null`), Q-14 (`ownerTeam` в проде).
