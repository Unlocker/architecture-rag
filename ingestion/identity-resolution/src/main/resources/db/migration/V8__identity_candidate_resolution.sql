-- Жизненный цикл кандидата (UNLOCKER-282): OPEN -> RESOLVED после подтверждения оператором (approve кандидата).
-- resolved_at/resolved_by пусты у OPEN. Только аддитивно: PK и данные не меняются, старые строки остаются OPEN.
-- Право SELECT для archrag_console_ro (V6) выдано на таблицу целиком и покрывает новые колонки; GRANT здесь не нужен,
-- а роль создаётся в миграции ingestion-service и в модуле identity-resolution может отсутствовать.
ALTER TABLE identity_candidate
    ADD COLUMN resolved_at timestamptz,
    ADD COLUMN resolved_by text,
    ADD CONSTRAINT ck_identity_candidate_status CHECK (status IN ('OPEN', 'RESOLVED'));
