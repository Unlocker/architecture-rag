# normalize-event — Нормализация одного события

> Use case модуля [`../`](../requirements.md). `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`, `NT/` = `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/`.

## Обзор
Вход `Normalizer.normalize(CanonicalEvent, RawPayloadRef)`: проверить тип события, источник и версию схемы, собрать `SourceRecord`, отдать payload маппер­у источника и вернуть `Normalized` либо `Quarantined` 🟢 (`NZ/Normalizer.java:54-86`). Вызывается `EventProcessor` после перехода события в `VALIDATED` (`EventProcessor.java:212-216`) 🟢.

## Бизнес-правила
- Порядок проверок строго: тип → источник → версия схемы → маппер (BR-01…BR-03 в [`../requirements.md`](../requirements.md)) 🟢.
- Источник распознаётся только по `urn:corp:` + точный код из {`eam`,`scm`,`cmdb`,`deploymap`}; маппер должен быть зарегистрирован 🟢 (`NZ/Normalizer.java:58-60,92-103`).
- Версия схемы — всё после `urn:corp:schema:asset-upserted:` (строка, без разбора числа); сравнение со `Set<String>` маппера 🟢 (`:105-107`).
- `SourceRecord.active = true` всегда; `sourceVersion` — строка `data.sourceVersion().value()`; `contentHash` — `raw.contentHash()` 🟢.
- Ошибки маппера: `NormalizationException` → `(e.code(), e.getMessage())`; `IllegalArgumentException` → `INVALID_PAYLOAD`, фиксированный текст 🟢. Прочие исключения пробрасываются 🟢.
- Причина карантина не содержит значений из источника 🟢.

## Функциональные требования
| ID | Требование | Приоритет | Критерий |
|---|---|---|---|
| RF-NE-01 | Возвращать `Quarantined("UNSUPPORTED_EVENT_TYPE", "event type is not supported")` для любого типа, кроме `architecture.asset.upserted.v1` | Must | AC-NE-02 |
| RF-NE-02 | Возвращать `Quarantined("UNKNOWN_SOURCE", "no mapper for event source")` для неизвестного URN или источника без маппера | Must | AC-NE-03 |
| RF-NE-03 | Возвращать `Quarantined("UNKNOWN_SCHEMA_VERSION", "dataschema version is not supported")` | Must | AC-NE-04 |
| RF-NE-04 | Строить `SourceRecord` по правилам выше и вызывать `mapper.map(Input(record, payload, event.time()), sink)` | Must | AC-NE-01 |
| RF-NE-05 | Переводить `NormalizationException`/`IllegalArgumentException` в `Quarantined` | Must | AC-NE-05, AC-NE-06 |
| RF-NE-06 | Повторный вызов с тем же событием и тем же состоянием портов даёт равный результат | Must | AC-NE-07 |

## НФТ
| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Причины фиксированы или содержат только имя поля | `NZ/Normalizer.java:81-83`; `NT/NormalizerTest#invariantViolationReasonDoesNotLeakSourceValues` | 🟢 |
| Идемпотентность | Нет побочных эффектов, результат равен при повторе | `NZ/Normalizer.java:18-19`; `NT/NormalizerTest#normalizingSameEventTwiceGivesEqualResult` | 🟢 |

## Критерии приёмки
```gherkin
# AC-NE-01 позитив — NormalizerTest#eamTeamMapsToTeam
Дано событие urn:corp:eam / urn:corp:schema:asset-upserted:1 / TEAM TEAM-PAY, sourceVersion "5", raw (key raw/1, hash abc123), time T
Когда normalize(event, raw)
Тогда Normalized; первая команда UpsertNode с record.key=(EAM,TEAM,TEAM-PAY), sourceVersion="5", contentHash="abc123", fetchedAt=T

# AC-NE-02 негатив — NormalizerTest#unsupportedEventTypeIsQuarantined
Дано type = architecture.asset.deleted.v1
Тогда Quarantined, errorCode = UNSUPPORTED_EVENT_TYPE

# AC-NE-03 негатив — NormalizerTest#unknownSourceIsQuarantined
Дано source = urn:corp:hr
Тогда Quarantined, errorCode = UNKNOWN_SOURCE

# AC-NE-04 негатив — NormalizerTest#unknownSchemaVersionIsQuarantined, #foreignSchemaUrnIsQuarantined
Дано dataschema = urn:corp:schema:asset-upserted:2
Тогда Quarantined("UNKNOWN_SCHEMA_VERSION", "dataschema version is not supported")
Дано dataschema = urn:other:thing:1
Тогда Quarantined UNKNOWN_SCHEMA_VERSION

# AC-NE-05 негатив — NormalizerTest#missingRequiredFieldIsQuarantinedWithoutPayloadValues
Дано EAM IT_SYSTEM {name: null, description: "SECRET-TEXT"}
Тогда Quarantined MISSING_REQUIRED_FIELD; reason содержит "name" и не содержит "SECRET-TEXT"

# AC-NE-06 негатив — NormalizerTest#cmdbKindMismatchIsQuarantined
Дано CMDB {hostname:"h", kind:"PHYSICAL_SERVER", hypervisorRef:"hv"}
Тогда Quarantined("INVALID_PAYLOAD", "payload violates canonical model invariants")

# AC-NE-07 — NormalizerTest#normalizingSameEventTwiceGivesEqualResult
Дано одно и то же событие  Когда normalize вызван дважды  Тогда результаты равны
```
Тестов на `raw == null` и на исключения портов нет 🟡 (Q-06 в [`../questions.md`](../questions.md)).

## MoSCoW
| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-NE-01…RF-NE-05 | Must | Вход каждого события в конвейер |
| RF-NE-06 | Must | Replay/rebuild |
| Расширение: поддержка нескольких версий схемы | Won't | Все маппера — только `"1"` (`EamMapper.java:61-63` и др.) |

## Трассировка
| Файл | Символ | Покрытие |
|---|---|---|
| `NZ/Normalizer.java` | `normalize`, `sourceCode`, `schemaVersion`, `quarantine` | 🟢 |
| `NZ/NormalizationResult.java` | `Normalized`, `Quarantined` | 🟢 |
| `NT/NormalizerTest.java` | тесты выше | 🟢 |
