# contracts/event-schemas, Вопросы к человеку

| ID | Вопрос | Контекст | Evidence |
|----|--------|----------|----------|
| Q-01 | Нужен ли штатный путь `QUARANTINED → RETRYING` (операция оператора), или статус `QUARANTINED` намеренно остаётся конечным? Если конечный, убрать ли переход из автомата? | Контракт разрешает переход, но админский REST его не реализует, а `EventProcessor` считает `QUARANTINED` завершённым | `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/ProcessingStatus.java:38`; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:99-105,190-192`; `_reversa_sdd/detective-gaps.md` Р-2 |
| Q-02 | Планируется ли перевод `PROJECTED → SUPERSEDED`, или значение следует убрать из контракта? | Недостижимо; смысл «вытеснено новой версией» в коде реализован через `IGNORED_OLD_VERSION`/`SAME` | `ProcessingStatus.java:16,39`; `docs/00-vision.md:385`; `docs/02-data-model.md` §5.2; Р-1 | 🟢 [UNLOCKER-281: реализовано]: `SUPERSEDED` удалён |
| Q-03 | Переносить ли константы типов событий (`deleted`, `snapshot-complete`), схем и префиксов id (`snap:`, `reconcile:`) в `event-schemas`? | Литералы продублированы в 5+ местах; связь без теста | `CanonicalEvent.java:31`; Н-5; matrix §2 № 1 |
| Q-04 | Требуется ли в контракте ограничить длину/формат `contentHash` (SHA-256 hex) и `key`? | Сейчас проверяется только непустота | `RawPayloadRef.java:10-11` |
| Q-05 | Должен ли `compareTo`/`equals` `SourceVersion` быть согласованы (нормализация ведущих нулей)? | `"007"` и `"7"` равны по `compareTo`, но не по `equals` | `SourceVersion.java:10-21` |
| Q-06 | Какое исключение должен бросать `RawPayloadStore.get` при расхождении hash (типизированное или любое непроверяемое)? | Javadoc: «бросает исключение» | `RawPayloadStore.java:9-10` |
