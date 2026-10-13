# normalize-event — дизайн

> Ссылка на общий дизайн: [`../design.md`](../design.md) §1-2. Здесь — детали ветвления `Normalizer.normalize` (`NZ/Normalizer.java:54-86`).

## Interface
`NormalizationResult normalize(CanonicalEvent event, RawPayloadRef raw)` 🟢. Ничего не бросает для контрактных нарушений payload; может бросить `NullPointerException` (например `raw == null`, `Normalizer.java:72`) и любое иное `RuntimeException` порта 🟢.

## Основной поток
1. `CanonicalEvent.TYPE_ASSET_UPSERTED.equals(event.type())` (`:55`). `CanonicalEvent` гарантирует непустые `id/source/type/dataschema`, не-`null` `time`/`data` (`ES/CanonicalEvent.java:34-40`) — проверок на `null` в `Normalizer` нет.
2. `sourceCode(event.source())` (`:92-103`): не начинается с `urn:corp:` → `null`; иначе `switch (суффикс)`: `"eam"`→`EAM`, `"scm"`→`SCM`, `"cmdb"`→`CMDB`, `"deploymap"`→`DEPLOYMAP`, `default`→`null`. `mapper = source == null ? null : mappers.get(source)`; `null` → `UNKNOWN_SOURCE` (`:58-62`). Тестируемый случай: `urn:corp:hr`.
3. `schemaVersion(dataschema)` (`:105-107`): суффикс после `urn:corp:schema:asset-upserted:` либо `null`. `null` или `!mapper.supportedSchemaVersions().contains(v)` → `UNKNOWN_SCHEMA_VERSION` (`:63-66`).
4. `data = event.data()`; `SourceRecord(new SourceKey(source, data.sourceType(), data.sourceId()), data.sourceVersion().value(), raw.contentHash(), event.time(), true)` (`:67-74`). Конструктор `SourceRecord` требует непустые `sourceVersion`, `contentHash`, не-`null` `fetchedAt` (`CM/provenance/SourceRecord.java`) 🟢.
5. `sink = new CommandSink(record.key(), resolver)` (`:75`).
6. `try { mapper.map(new Input(record, data.payload(), event.time()), sink); }` (`:76-77`):
   - `catch NormalizationException e` → `quarantine(e.code(), e.getMessage())` (`:78-79`);
   - `catch IllegalArgumentException` → `quarantine("INVALID_PAYLOAD", "payload violates canonical model invariants")` (`:80-83`). Комментарий в коде: текст исключения может содержать значения источника.
7. `return sink.result()` → `Normalized(commands, unresolved, warnings)` (`:85`).

## Альтернативные потоки
- Любое иное `RuntimeException` из `map` (включая `DateTimeParseException` — он сам ловится в `Payload.optionalInstant`, `NZ/Payload.java:70-72`, остальное не ловится) — выходит наружу 🟢.
- Результат `Normalized` может иметь пустой `commands` (например `SERVICE_DEPENDENCY` с двумя неизвестными сервисами) 🟢 (`NT/NormalizerTest#eamServiceDependencyWithUnknownServiceIsUnresolved`).

## Зависимости
`CanonicalMapper` (выбор по `EnumMap<SourceSystemCode, CanonicalMapper>`, `:30`), `ReferenceResolver` (через `CommandSink`), `CanonicalEvent`, `AssetEventData`, `RawPayloadRef` (`ES`), `SourceKey/SourceRecord/SourceSystemCode` (`CM/provenance`).

## Решения
| Решение | Evidence | Уверенность |
|---|---|---|
| Карантин как значение результата, а не исключение | `NZ/NormalizationResult.java:30-34` | 🟢 |
| Источник сопоставляется ручным `switch` | `NZ/Normalizer.java:96-102` | 🟢 (риск рассинхрона: Q-11) |
| Версия схемы — строковое сравнение суффикса | `NZ/Normalizer.java:105-107` | 🟢 |
| Одинаковый `eventId` при разных `sourceVersion` — забота не нормализатора (дедуп/версия — `EventProcessor`) | `EventProcessor.java:208` (`decideBeforeWrite` до `normalize`) | 🟢 |

## Связи с другими спецификациями
Матрица влияния: №2 (таблица кодов источников), №1 (литералы типов `deleted`/`snapshot-complete` — они маршрутизируются в `EventProcessor` до `Normalizer`, поэтому сюда доходят только `upserted`; остальные получили бы `UNSUPPORTED_EVENT_TYPE`), №3 (регистр источника) — см. [`../design.md`](../design.md) §6.2.

## Риски и пробелы
- 🟡 `raw == null` → NPE (журнал допускает `payload_key IS NULL`, `code-analysis.md` §Q2.5); Q-06.
- 🟡 Два маппера одного источника: побеждает последний (`NZ/Normalizer.java:35`).
- 🟡 Источник `MANUAL` без маппера → `UNKNOWN_SOURCE` (Q-11).
