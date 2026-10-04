-- Deterministic mapping (source, source_type, source_id) -> gid и approved crosswalk.
-- Хранится в PostgreSQL, а не выводится из графа: rebuild графа из raw даёт те же gid.
CREATE TABLE identity_mapping (
    source      text        NOT NULL,
    source_type text        NOT NULL,
    source_id   text        NOT NULL,
    gid         uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (source, source_type, source_id)
);

CREATE INDEX ix_identity_mapping_gid ON identity_mapping (gid);

-- Подтверждённые соответствия. Пара хранится в нормализованном порядке (left < right, порядок задаёт приложение), повторное утверждение идемпотентно.
CREATE TABLE approved_crosswalk (
    left_source  text        NOT NULL,
    left_type    text        NOT NULL,
    left_id      text        NOT NULL,
    right_source text        NOT NULL,
    right_type   text        NOT NULL,
    right_id     text        NOT NULL,
    approved_by  text        NOT NULL,
    approved_at  timestamptz NOT NULL,
    PRIMARY KEY (left_source, left_type, left_id, right_source, right_type, right_id)
);
