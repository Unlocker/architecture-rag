# Блок-схемы: ingestion/identity-resolution

> Archaeologist, R.2c. Пути относительно `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/`.

## `approve(Crosswalk)` (`PostgresIdentityMapping.java:80-122`) 🟢

```mermaid
flowchart TD
  A["approve(crosswalk)"] --> O["упорядочить пару: lo < hi (SourceKeys.compare)"]
  O --> B["BEGIN"]
  B --> S["ga = select(lo); gb = select(hi)"]
  S --> D{"оба есть и gid различаются?"}
  D -- да --> X["CrosswalkConflictException (ROLLBACK)"]
  D -- нет --> G["gid = ga ?? gb ?? randomUUID()"]
  G --> I["INSERT identity_mapping ON CONFLICT DO NOTHING для lo и hi; перечитать gid"]
  I --> R{"gid(lo) == gid(hi)?"}
  R -- нет --> X
  R -- да --> W["INSERT approved_crosswalk ON CONFLICT DO NOTHING"]
  W --> C["COMMIT → gid"]
```

## `IdentityCandidates.record` (`PostgresIdentityCandidates.java:78-102, 190-239`) 🟢

```mermaid
flowchart TD
  A["record(key, label, features)"] --> F["family = LabelFamily.of(label); сортировка признаков"]
  F --> L1["pg_advisory_xact_lock(key|ключ)"]
  L1 --> L2["lock(семейство|вид|значение) для сильных признаков"]
  L2 --> RF["DELETE + INSERT identity_feature ключа"]
  RF --> LOOP["для каждого сильного признака: FIND_OTHERS\n(тот же feature+family+value, другой ключ,\nне общий gid, LIMIT 51)"]
  LOOP --> M{"найдено > 50?"}
  M -- да --> SK["WARN, признак пропущен"]
  M -- нет --> ADD["matches[other] += признак"]
  ADD --> WK["слабые признаки (OWNER): добавить, если у партнёра такой же"]
  SK --> WK
  WK --> UP["для каждой пары: upsertCandidate\nON CONFLICT DO UPDATE matched, score, updated_at\n(first_seen_at и status не меняются)"]
  UP --> CM["COMMIT"]
```

## Пересчёт конфликтов свойств (`PostgresSourceConflicts.java:81-103, 210-271`) 🟢

```mermaid
flowchart TD
  A["assertProperties(key, gid, label, values)"] --> L["advisory-lock conflict|gid"]
  L --> D["DELETE property_assertion записи"]
  D --> I["INSERT по свойству: authoritative = matrix.isAuthoritative(label, property, source)"]
  I --> Q["DIVERGING: неавторитетное d.value <> авторитетное m.value\n(m — первый по ключу из мастеров)"]
  Q --> U["UPSERT source_conflict status=OPEN, kind=PROPERTY"]
  U --> K["OPEN_KEYS (kind=PROPERTY, OPEN) ∖ ещё расходящиеся"]
  K --> RS["UPDATE status=RESOLVED, resolved_at=now"]
  RS --> OUT["SELECT открытые конфликты gid"]
```

## Состояния `source_conflict` 🟢

```mermaid
stateDiagram-v2
  [*] --> OPEN: расхождение (upsert)
  OPEN --> OPEN: значения изменились (updated_at меняется)
  OPEN --> RESOLVED: перестали расходиться / retract / нет мастера (RELATION)
  RESOLVED --> OPEN: расхождение возобновилось (opened_at сохраняется, resolved_at = NULL)
```
