-- Inbox событий, checkpoints consumer-ов и DLQ. Raw payload лежит в S3, здесь только ключ и hash.
CREATE TABLE inbox_event (
    source         text        NOT NULL,
    event_id       text        NOT NULL,
    type           text        NOT NULL,
    subject        text,
    source_type    text        NOT NULL,
    source_id      text        NOT NULL,
    source_version text        NOT NULL,
    correlation_id text,
    sync_run_id    text,
    schema_version text        NOT NULL,
    status         text        NOT NULL,
    attempts       integer     NOT NULL DEFAULT 0,
    error_code     text,
    error_reason   text,
    payload_key    text,
    payload_hash   text,
    received_at    timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT uq_inbox_event UNIQUE (source, event_id)
);

CREATE INDEX ix_inbox_event_status ON inbox_event (status, received_at);
CREATE INDEX ix_inbox_event_object ON inbox_event (source, source_type, source_id);

CREATE TABLE consumer_checkpoint (
    consumer   text        NOT NULL,
    source     text        NOT NULL,
    cursor     text        NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, source)
);

CREATE TABLE dlq_entry (
    id          bigserial   PRIMARY KEY,
    source      text        NOT NULL,
    event_id    text        NOT NULL,
    reason      text        NOT NULL,
    error_code  text        NOT NULL,
    payload_key text,
    created_at  timestamptz NOT NULL,
    replayed_at timestamptz,
    FOREIGN KEY (source, event_id) REFERENCES inbox_event (source, event_id)
);

-- Одна необработанная запись DLQ на событие: повторный toDlq не плодит дубли.
CREATE UNIQUE INDEX uq_dlq_entry_open ON dlq_entry (source, event_id) WHERE replayed_at IS NULL;
