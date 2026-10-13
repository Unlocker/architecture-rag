# normalize-event — задачи

## Пререквизиты
- [ ] Выполнены T-01…T-04 из [`../tasks.md`](../tasks.md) (типы, порты, `Payload`, `CommandSink`).
- [ ] Хотя бы один `CanonicalMapper` (T-06…T-09).

## Задачи
- [ ] T-NE-01 Константы `SOURCE_PREFIX = "urn:corp:"`, `SCHEMA_PREFIX = "urn:corp:schema:asset-upserted:"`; `EnumMap<SourceSystemCode, CanonicalMapper>`; конструктор `requireNonNull(resolver)`.
  - Origin: `NZ/Normalizer.java:27-36`
  - Критерий готовности: два маппера одного источника — побеждает последний; `resolver == null` → NPE «resolver».
  - Уверенность: 🟢
- [ ] T-NE-02 `normalize` в порядке: тип → источник → схема → `SourceRecord` → `map` → перехват исключений → `sink.result()`.
  - Origin: `NZ/Normalizer.java:54-86`
  - Критерий готовности: AC-NE-01…AC-NE-07.
  - Уверенность: 🟢
- [ ] T-NE-03 `sourceCode` — `switch` по суффиксу URN, `schemaVersion` — суффикс схемы.
  - Origin: `NZ/Normalizer.java:92-107`
  - Критерий готовности: `urn:corp:hr` → `UNKNOWN_SOURCE`; `urn:other:thing:1` → `UNKNOWN_SCHEMA_VERSION`.
  - Уверенность: 🟢
- [ ] T-NE-04 Фиксированные тексты причин (см. [`../contracts.md`](../contracts.md) §3).
  - Origin: `NZ/Normalizer.java:56,61,65,83`
  - Критерий готовности: тексты совпадают побуквенно (тесты сравнивают `Quarantined` целиком: `NormalizerTest#unknownSchemaVersionIsQuarantined`, `#cmdbKindMismatchIsQuarantined`).
  - Уверенность: 🟢

## Тесты
- [ ] TT-NE-01 `NormalizerTest#eamTeamMapsToTeam` — позитив, проверка полей `SourceRecord`.
- [ ] TT-NE-02 `#unsupportedEventTypeIsQuarantined`, `#unknownSourceIsQuarantined`, `#unknownSchemaVersionIsQuarantined`, `#foreignSchemaUrnIsQuarantined` — негатив ворот.
- [ ] TT-NE-03 `#missingRequiredFieldIsQuarantinedWithoutPayloadValues`, `#cmdbKindMismatchIsQuarantined`, `#invariantViolationReasonDoesNotLeakSourceValues`.
- [ ] TT-NE-04 `#normalizingSameEventTwiceGivesEqualResult`.
- [ ] TT-NE-05 (нет в коде 🟡) Тест на `raw == null` и на исключение из `ReferenceResolver` — зафиксировать решение Q-06.

## Порядок
T-NE-01 → T-NE-03 → T-NE-02 → T-NE-04; тесты после первого маппера.

## Открытые 🔴/🟡
Q-06, Q-11 ([`../questions.md`](../questions.md)).
