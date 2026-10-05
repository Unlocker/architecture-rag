-- Признаки идентичности записей и кандидаты на совпадение (E3.2). Кандидат только фиксирует подозрение: canonical-узлы не объединяются, gid не меняется.
CREATE TABLE identity_feature (
    source      text        NOT NULL,
    source_type text        NOT NULL,
    source_id   text        NOT NULL,
    gid         uuid        NOT NULL,
    label       text        NOT NULL,
    feature     text        NOT NULL,
    value       text        NOT NULL,
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (source, source_type, source_id, feature)
);

CREATE INDEX ix_identity_feature_lookup ON identity_feature (label, feature, value);

-- Пара хранится в нормализованном порядке (left_gid < right_gid, порядок задаёт приложение), повторное наблюдение обновляет запись.
CREATE TABLE identity_candidate (
    left_gid   uuid         NOT NULL,
    right_gid  uuid         NOT NULL,
    label      text         NOT NULL,
    features   text[]       NOT NULL,
    score      numeric(4,3) NOT NULL,
    status     text         NOT NULL DEFAULT 'OPEN',
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    PRIMARY KEY (left_gid, right_gid)
);

CREATE INDEX ix_identity_candidate_right ON identity_candidate (right_gid);
