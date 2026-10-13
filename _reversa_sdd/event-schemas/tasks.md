# contracts/event-schemas, Задачи реконструкции

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`, `EST/` = `contracts/event-schemas/src/test/java/io/github/unlocker/archrag/eventschemas/`. Требования: [`requirements.md`](requirements.md); дизайн: [`design.md`](design.md).

## Пре-реквизиты
- [ ] Модуль `contracts/event-schemas` в `contracts/pom.xml` (`<module>event-schemas</module>`) 🟢 (`contracts/pom.xml`), артефакт `event-schemas`, родитель `contracts` (`contracts/event-schemas/pom.xml:7-13`).
- [ ] Зависимость от `canonical-model` без версии (версия в `dependencyManagement` корня) 🟢 (`contracts/event-schemas/pom.xml:15-19`). Фактически не используется 🟡.
- [ ] Java 21 (records, `switch`-выражения); библиотек сверх JDK и тестовых (JUnit 5, AssertJ) нет 🟢.
- [ ] Пакет `io.github.unlocker.archrag.eventschemas`; `package-info.java`; маркер `EventSchemasModule` (final, private ctor) — иначе JAR пуст, а smoke-тест не видит classpath 🟢 (`ES/EventSchemasModule.java:3-6`).

## Задачи

- [ ] T-01 `SourceVersion` (record, `Comparable`), regex `[0-9]{1,40}`, `compareTo` через `BigInteger`, `isOlderThan`.
  - Origin: `ES/SourceVersion.java:10-26`
  - Критерий готовности: `new SourceVersion("99").isOlderThan(new SourceVersion("184"))` истинно; `"v2"`, `null`, пустая, 41 цифра отвергаются IAE.
  - Уверенность: 🟢
- [ ] T-02 `AssetEventData` (record) с копией `payload` с сохранением `null`-значений.
  - Origin: `ES/AssetEventData.java:14-30`
  - Критерий готовности: изменение исходной карты после создания не видно; `containsEntry("language", null)`; `payload() == null` → пустая карта.
  - Уверенность: 🟢
- [ ] T-03 `CanonicalEvent` (record + константы + `specversion()`).
  - Origin: `ES/CanonicalEvent.java:17-52`
  - Критерий готовности: AC-01, AC-02, AC-06 из `requirements.md`.
  - Уверенность: 🟢
- [ ] T-04 `ProcessingStatus` + `canTransitionTo` по таблице [`design.md`](design.md).
  - Origin: `ES/ProcessingStatus.java:8-42`
  - Критерий готовности: все пары (10×10) совпадают с таблицей; терминальные состояния только «в себя».
  - Уверенность: 🟢
- [ ] T-05 DTO журнала: `JournalEntry` (15 компонентов в указанном порядке), `StoredEvent`, `Checkpoint`, `ObjectRef`, `SnapshotContents`, `RawPayloadRef`.
  - Origin: `ES/JournalEntry.java:14-29`, `ES/StoredEvent.java:12-17`, `ES/Checkpoint.java:13`, `ES/ObjectRef.java:7`, `ES/SnapshotContents.java:14-18`, `ES/RawPayloadRef.java:7-13`
  - Критерий готовности: сигнатуры и проверки конструкторов как в таблице `design.md`.
  - Уверенность: 🟢
- [ ] T-06 `JournalKey`, `JournalQuery` (константы `REPLAY_PREFIX`, `MAX_LIMIT`, метод `after`), `JournalReader`.
  - Origin: `ES/JournalKey.java:11-22`, `ES/JournalQuery.java:18-39`, `ES/JournalReader.java:12-29`
  - Критерий готовности: [`journal-keyset-read`](journal-keyset-read/requirements.md).
  - Уверенность: 🟢
- [ ] T-07 Порты `EventJournal`, `RawPayloadStore`, `CanonicalEventPublisher` с Javadoc-гарантиями.
  - Origin: `ES/EventJournal.java:13-61`, `ES/RawPayloadStore.java:4-11`, `ES/CanonicalEventPublisher.java:7-15`
  - Критерий готовности: [`journal-append-dedup-dlq`](journal-append-dedup-dlq/requirements.md); реализации (вне модуля) проходят контрактные сценарии.
  - Уверенность: 🟢
- [ ] T-08 (предложение, не реализовано) Вынести константы типов `deleted`/`snapshot-complete` и схем в контракт; префиксы `snap:`/`reconcile:`.
  - Origin: `_reversa_sdd/code-analysis.md` §E5; `tech-debt.md` Н-5
  - Критерий готовности: ни одного дубликата литералов в `adapter-core`, `graph-projector`, `ingestion-service`; тест на равенство.
  - Уверенность: 🔴 (решение архитектора)

## Тесты

Существуют (12 `@Test`, 5 классов) 🟢:
- [ ] TT-01 `EST/SourceVersionTest.java` (`comparesNumericallyNotLexically`, `rejectsNonNumeric`)
- [ ] TT-02 `EST/AssetEventDataTest.java` (`payloadKeepsNullValuesAsNotProvided`)
- [ ] TT-03 `EST/CanonicalEventTest.java` (`buildsEnvelope`, `rejectsMissingId`, `dataRequiresSourceId`)
- [ ] TT-04 `EST/ProcessingStatusTest.java` (`happyPathMovesForward`, `projectedCannotRollBack`, `terminalStatesOnlyStayPut`, `quarantineExitsOnlyThroughRetrying`, `failuresAreReachableFromActiveStages`)
- [ ] TT-05 `EST/EventSchemasSmokeTest.java` (`moduleMarkerIsOnClasspath`)

Отсутствуют (рекомендуются при реконструкции) 🟡:
- [ ] TT-06 `JournalQuery`: `limit` 0, 1001 → IAE; `receivedFrom == receivedTo`, `from > to` → IAE; `after(next)` сохраняет прочие поля.
- [ ] TT-07 `JournalKey`/`StoredEvent`: `null` → NPE; `JournalKey.of`.
- [ ] TT-08 `CanonicalEvent`: `time == null`, `data == null`, пустые `source`/`type`/`dataschema`.
- [ ] TT-09 `SourceVersion`: 40 цифр принимается, 41 отвергается; `"007"` vs `"7"`: `compareTo == 0`, `equals == false`.
- [ ] TT-10 `RawPayloadRef` и `SnapshotContents` (защитная копия).
- [ ] TT-11 `ProcessingStatus`: полная матрица 10×10 против эталонной таблицы (сейчас проверены только выборочные пары).
- [ ] TT-12 Контрактный тест портов (абстрактный `EventJournalContract`), который прогоняют `PostgresEventJournal` и `InMemoryJournal`; в модуле такого теста нет, дедупликация проверяется только в IT реализации 🔴.

## Порядок
1. T-01, T-02 (нет зависимостей) → T-03 (использует оба).
2. T-04 (независимо) → T-05 → T-06 → T-07 (порты используют все типы).
3. TT-01…TT-05 вместе с соответствующими задачами; TT-06…TT-12 после.

## Открытые 🔴
R-01 … R-05 из [`requirements.md`](requirements.md); вопросы к человеку — в [`questions.md`](questions.md).
