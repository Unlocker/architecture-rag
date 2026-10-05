-- Аудит админских операций (replay, rebuild, reconcile, crosswalk). Запись STARTED делается до операции.
CREATE TABLE admin_audit (
    id          bigserial   PRIMARY KEY,
    operation   text        NOT NULL,
    actor       text        NOT NULL,
    request     jsonb       NOT NULL,
    replay_id   text,
    status      text        NOT NULL,
    result      jsonb,
    error       text,
    started_at  timestamptz NOT NULL,
    finished_at timestamptz
);

CREATE INDEX ix_admin_audit_started ON admin_audit (started_at);
CREATE INDEX ix_admin_audit_replay ON admin_audit (replay_id);
