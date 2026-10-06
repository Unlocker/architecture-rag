-- Конфликты источников по авторитетности (E3.3): утверждения свойств и открытые/закрытые расхождения.
-- Неавторитетное значение в граф не попадает; здесь оно хранится, чтобы конфликт не зависел от порядка событий.

-- Последние утверждённые значения свойств записи; при upsert записи заменяются целиком (delete + insert),
-- tombstone их удаляет. value — текстовое представление (enum -> name, Instant -> UTC ISO).
CREATE TABLE property_assertion (
    source        text        NOT NULL,
    source_type   text        NOT NULL,
    source_id     text        NOT NULL,
    property      text        NOT NULL,
    gid           uuid        NOT NULL,
    label         text        NOT NULL,
    value         text        NOT NULL,
    authoritative boolean     NOT NULL,
    asserted_at   timestamptz NOT NULL,
    PRIMARY KEY (source, source_type, source_id, property)
);

CREATE INDEX ix_property_assertion_gid ON property_assertion (gid, property);

-- Расхождение неавторитетной записи с мастером. Строка не удаляется: RESOLVED остаётся историей,
-- повторное расхождение снова открывает её (opened_at не меняется).
CREATE TABLE source_conflict (
    gid            uuid        NOT NULL,
    property       text        NOT NULL,
    dissent_source text        NOT NULL,
    dissent_type   text        NOT NULL,
    dissent_id     text        NOT NULL,
    dissent_value  text        NOT NULL,
    master_value   text        NOT NULL,
    master_source  text        NOT NULL,
    master_type    text        NOT NULL,
    master_id      text        NOT NULL,
    status         text        NOT NULL DEFAULT 'OPEN',
    opened_at      timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    resolved_at    timestamptz,
    PRIMARY KEY (gid, property, dissent_source, dissent_type, dissent_id)
);
