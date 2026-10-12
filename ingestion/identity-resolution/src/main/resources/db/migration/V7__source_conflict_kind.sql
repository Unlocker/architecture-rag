-- Вид конфликта источников (H.5): PROPERTY — расхождение свойства одного узла (как раньше), RELATION —
-- расхождение связи (например, система EAM из chart развёртывания и система сервиса). Только аддитивно:
-- таблица живая, PK не меняется, старые строки получают PROPERTY. Право SELECT из V6 покрывает новую колонку.
ALTER TABLE source_conflict ADD COLUMN kind text NOT NULL DEFAULT 'PROPERTY';
