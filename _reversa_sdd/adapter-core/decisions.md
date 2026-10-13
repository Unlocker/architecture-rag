# adapters/adapter-core, Решения

> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`. ADR — только ссылки (`docs/adr/`).

| № | Решение | Код | ADR / docs | Оценка |
|---|---|---|---|---|
| D-1 | Адаптер пишет в inbox (PostgreSQL) и raw в S3; прямого пути в Neo4j нет | `AC/InboxWriter.java:34-48`, `adapters/adapter-core/pom.xml:17-32` | [0006](../../docs/adr/0006-postgres-inbox-event-backbone.md), [0007](../../docs/adr/0007-s3-raw-storage-seaweedfs.md), [0002](../../docs/adr/0002-four-contours.md) | 🟢 |
| D-2 | Мастер-системы — заглушки; `HttpSourceConnector` без аутентификации к источнику | `AC/HttpSourceConnector.java:80-86` | [0010](../../docs/adr/0010-master-systems-are-stubs.md) | 🟢 |
| D-3 | Удаление только через tombstone-событие; missing set — только после маркера `snapshot-complete` | `AC/EventMapper.java:29-32`, `AC/Poller.java:164-172` | [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) | 🟢 (расхождение формулировки HWM — 🔴 R-02) |
| D-4 | Reconcile не удаляет сам: адаптер только перечитывает полный snapshot и пишет маркер; удаления делает `Reconciler` в graph-projector | `AC/Poller.java:98-105` (Javadoc), `docs/03-interfaces.md:378` | [0013](../../docs/adr/0013-operator-ops-via-admin-endpoint.md) | 🟢 |
| D-5 | Webhook → `202` только после записи в inbox, SLA «webhook → граф» опирается на быструю фиксацию | `AC/WebhookHandler.java:105-106` | [0014](../../docs/adr/0014-sla-webhook-to-neo4j.md) | 🟢 |
| D-6 | Java 21, виртуальные потоки, без Spring/Kafka/Reactor в адаптерах; JDK `HttpServer` и `HttpClient` | `AC/WebhookServer.java:53`, `AC/SourceAdapter.java:76, 101`, `AC/HttpSourceConnector.java:37-38` | [0015](../../docs/adr/0015-java21-spring-maven-stack.md) | 🟢 |
| D-7 | Новая зависимость (JSON-библиотека) не вводится — собственный `Json` | `AC/Json.java:16`; `adapters/adapter-core/pom.xml` без Jackson | `CLAUDE.md` («новая библиотека только по задаче») | 🟡 (мотив выведен из правила; в коде не оговорён) |
| D-8 | Один экземпляр адаптера на источник; advisory lock в журнале отложен | `AC/Poller.java:34-35` | `docs/05-production-gap-list.md:52` (п. 4.2) | 🟢 |
| D-9 | `/control/snapshot` без аутентификации, наружу не публикуется | `AC/WebhookServer.java:18-22` | `docs/03-interfaces.md:33, 560` | 🟢 (риск принят) |
