-- Третья ступень identity resolution (E3.2): текущие признаки записей и кандидаты на совпадение.
-- Кандидат только фиксирует подозрение: canonical-узлы не объединяются, gid не меняется.

-- Текущие признаки ключа; при upsert записи заменяются целиком (delete + insert в одной транзакции).
CREATE TABLE identity_feature (
    source       text        NOT NULL,
    source_type  text        NOT NULL,
    source_id    text        NOT NULL,
    feature      text        NOT NULL,
    value        text        NOT NULL,
    label_family text        NOT NULL,
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (source, source_type, source_id, feature)
);

CREATE INDEX ix_identity_feature_lookup ON identity_feature (feature, label_family, value);

-- Пара хранится в нормализованном порядке (left < right, порядок задаёт приложение, как у approved_crosswalk).
-- matched — массив {feature, value} из нормализованных значений. Повтор обновляет matched, score и updated_at,
-- first_seen_at и status не трогает.
CREATE TABLE identity_candidate (
    left_source   text         NOT NULL,
    left_type     text         NOT NULL,
    left_id       text         NOT NULL,
    right_source  text         NOT NULL,
    right_type    text         NOT NULL,
    right_id      text         NOT NULL,
    label_family  text         NOT NULL,
    matched       jsonb        NOT NULL,
    score         numeric(3,2) NOT NULL,
    status        text         NOT NULL DEFAULT 'OPEN',
    first_seen_at timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    PRIMARY KEY (left_source, left_type, left_id, right_source, right_type, right_id)
);

CREATE INDEX ix_identity_candidate_right ON identity_candidate (right_source, right_type, right_id);
