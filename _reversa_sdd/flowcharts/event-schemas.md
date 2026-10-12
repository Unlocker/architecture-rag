# Блок-схемы: contracts/event-schemas

> Archaeologist, R.2a. Пути относительно `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`.

## 1. Автомат `ProcessingStatus` (`ProcessingStatus.java:26-42`) 🟢

```mermaid
stateDiagram-v2
  [*] --> RECEIVED
  RECEIVED --> VALIDATED
  VALIDATED --> NORMALIZED
  NORMALIZED --> RESOLVED
  RESOLVED --> PROJECTED
  PROJECTED --> SUPERSEDED
  RECEIVED --> DUPLICATE
  RECEIVED --> IGNORED_OLD_VERSION
  VALIDATED --> IGNORED_OLD_VERSION
  NORMALIZED --> IGNORED_OLD_VERSION
  RESOLVED --> IGNORED_OLD_VERSION
  RETRYING --> IGNORED_OLD_VERSION
  RECEIVED --> QUARANTINED
  VALIDATED --> QUARANTINED
  NORMALIZED --> QUARANTINED
  RESOLVED --> QUARANTINED
  RETRYING --> QUARANTINED
  RECEIVED --> RETRYING
  VALIDATED --> RETRYING
  NORMALIZED --> RETRYING
  RESOLVED --> RETRYING
  RETRYING --> VALIDATED
  RETRYING --> NORMALIZED
  RETRYING --> RESOLVED
  RETRYING --> PROJECTED
  QUARANTINED --> RETRYING: решение оператора
  SUPERSEDED --> [*]
  DUPLICATE --> [*]
  IGNORED_OLD_VERSION --> [*]
```

Переход «в себя» разрешён из любого состояния (`:27-29`) 🟢. Диаграмма совпадает с `docs/02-data-model.md:239-270` (проверено построчно) 🟢.

## 2. Контракт `EventJournal.append` / `transition` / `toDlq` (`EventJournal.java:15-41`) 🟢

```mermaid
flowchart TD
  A["append(event, payloadRef, syncRunId)"] --> B{"(source, eventId) уже есть?"}
  B -- нет --> C[вставить строку RECEIVED] --> R1[вернуть JournalEntry]
  B -- да --> D["вернуть прежнюю запись со статусом DUPLICATE; строка не меняется"]

  T["transition(source, eventId, status, code, reason)"] --> T1{событие есть?}
  T1 -- нет --> TE1[IllegalArgumentException]
  T1 -- да --> T2{"canTransitionTo(status)?"}
  T2 -- нет --> TE2[IllegalStateException]
  T2 -- да --> T3[обновить статус, errorCode, errorReason]

  Q["toDlq(source, eventId, code, reason)"] --> Q1{"code пуст или события нет?"}
  Q1 -- да --> QE[IllegalArgumentException]
  Q1 -- нет --> Q2[статус QUARANTINED]
  Q2 --> Q3{"есть необработанная запись DLQ?"}
  Q3 -- да --> Q4[вторую не создавать]
  Q3 -- нет --> Q5[создать запись DLQ]
```

## 3. Сравнение версий и решение о применении (`SourceVersion.java:18-26`; решение принимает `GraphProjector`, не контракт) 🟡

```mermaid
flowchart TD
  A[новая версия V, применённая A] --> B{"V.isOlderThan(A)?"}
  B -- да --> C["IGNORED_OLD_VERSION — граф не меняется"]
  B -- нет --> D{"compareTo == 0?"}
  D -- да --> E["DUPLICATE (NOOP_SAME_VERSION), docs/03-interfaces.md §GraphProjector"]
  D -- нет --> F[применить]
```

Контракт содержит только `compareTo`/`isOlderThan`; ветка «равная версия» и исключение для tombstone описаны в `docs/03-interfaces.md` (общая семантика `GraphProjector.write`) и проверяются в R.2c 🟡.

## 4. Keyset-чтение журнала (`JournalReader.java:12-28`, `JournalQuery.java:27-39`) 🟢

```mermaid
flowchart TD
  A[JournalQuery] --> B{limit в 1..1000?}
  B -- нет --> E1[IAE]
  B -- да --> C{"from и to заданы и from >= to?"}
  C -- да --> E2[IAE]
  C -- нет --> D["read: строки (receivedAt, source, eventId) > after, не более limit"]
  D --> F{страница полна?}
  F -- да --> G["query.after(JournalKey.of(последняя))"] --> D
  F -- нет --> H[конец обхода]
```

## 5. Порядок записи и сдвиг checkpoint (контракт; вызывающий код — R.2c) 🟡

```mermaid
sequenceDiagram
  participant AD as Adapter
  participant S3 as RawPayloadStore
  participant J as EventJournal
  participant P as Projector
  participant N as Neo4j
  AD->>S3: put(source, bytes) → RawPayloadRef
  AD->>J: append(event, ref, syncRunId) → RECEIVED
  P->>J: transition(...VALIDATED→NORMALIZED→RESOLVED)
  P->>N: executeWrite(проекция)
  N-->>P: commit
  P->>J: transition(PROJECTED)
  P->>J: saveCheckpoint(consumer, source, cursor)
```

Последовательность выведена из Javadoc `RawPayloadStore.put` («уже сохранённый raw», `EventJournal.java:19`) и `saveCheckpoint` («только после коммита проекции», `:46-47`) 🟡.
