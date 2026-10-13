# Процедуры, функции, триггеры и представления

> Data Master, этап R.3 (UNLOCKER-268).

В миграциях V1–V7 нет `CREATE FUNCTION`, `CREATE PROCEDURE`, `CREATE TRIGGER`, `CREATE VIEW`, `CREATE MATERIALIZED VIEW` и `CREATE SEQUENCE` (прямой просмотр всех семи файлов) 🟢. В Neo4j нет процедур APOC и пользовательских процедур: проект использует только `db.index.fulltext.queryNodes` и `db.awaitIndexes()` из встроенных (тест `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java:84-86`; шаблон поиска — основной словарь §X.1) 🟡.

## Единственный процедурный блок: `DO $$` в V6

`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:6-12`:

```sql
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'archrag_console_ro') THEN
        CREATE ROLE archrag_console_ro LOGIN;
    END IF;
END
$$;
```

- **Назначение:** идемпотентно создать роль консоли без пароля. Роли в PostgreSQL общекластерные, а `CREATE ROLE IF NOT EXISTS` не существует, поэтому проверка выполнена через `pg_roles`.
- **Входы / выходы:** параметров нет, возвращаемого значения нет; побочный эффект — роль в кластере.
- **Требования:** пользователь миграции должен иметь `CREATEROLE` или быть суперпользователем; иначе миграция падает (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:3-4`, на стенде пользователь — `POSTGRES_USER` контейнера, `platform/docker/docker-compose.yml:102-107`) 🟢.
- **Гонка:** проверка и создание не защищены блокировкой; при одновременной миграции из двух процессов второй получит ошибку «role already exists». Flyway сериализует миграции собственной блокировкой на `flyway_schema_history`, поэтому на практике гонка закрыта, но только внутри Flyway 🟡.
- **Пароль:** вне миграции. `ALTER ROLE archrag_console_ro PASSWORD :'p'` выполняет `platform/docker/postgres/init-console-ro.sh:9` (пароль читается из Docker secret метакомандой `\set p \`cat ...\``, чтобы не попасть в argv и окружение) 🟢.

Следующие за блоком `GRANT` — декларативные, не процедурные: `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:14-16`.
