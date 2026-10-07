-- Read-only роль админ-консоли (access/admin-console). Пароль не задаётся: его ставит init-скрипт стенда
-- или тест (ALTER ROLE ... PASSWORD). Роль общекластерная, поэтому создание идемпотентно.
-- Для миграции пользователю ingestion нужно право CREATEROLE (или роль создаётся заранее администратором БД).
-- Только SELECT и только на таблицы, которые читает консоль. Raw payload в PostgreSQL не хранится
-- (inbox_event держит ключ и hash объекта S3).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'archrag_console_ro') THEN
        CREATE ROLE archrag_console_ro LOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO archrag_console_ro;
GRANT SELECT ON inbox_event, consumer_checkpoint, dlq_entry, identity_candidate, source_conflict, admin_audit
    TO archrag_console_ro;
