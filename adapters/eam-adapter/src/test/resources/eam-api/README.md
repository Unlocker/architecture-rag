# Fixtures EAM API

Ответы EAM в формате `docs/eam-api.yaml` («EAM Tool API» v0.4): тела `GET /api/<type>/` (голый массив `ArchObject[]`) и
метаданные типов. **Доступа к реальному EAM нет**, поэтому это не записанные ответы, а собранные по спеке данные.
Всё, чего нет в спеке, — допущение **«не подтверждено»**: имена типов (`platforms`, `itsystems`, `itproducts` как «ИТ-решение»),
id типов, поля `attrs` (`name`, `status`, `lifecycle`, `criticality`, `description`, `eam_id`), ссылки `solution` и
`platform` как массивы целочисленных id, формат `options`. Обоснование и полный список — раздел «Решения» в
`docs/06-eam-integration.md`. Когда появится доступ к EAM, fixtures перегенерируются записью реальных ответов.

| Каталог | Сценарий («Тестирование» в `06-eam-integration.md`) | Допущения |
|---|---|---|
| `types.json`, `options/` | метаданные типов: `/api/types/`, `/api/types/{id}/options/?kind=fields\|system` | формат `options`, id типов, множественность ссылок |
| `baseline/` | полный срез `ITSystem → Solution → Platform`, совпадает с seed заглушки `EamApiSeed` | `eam_id` = `systemCode` SCM; не подтверждено |
| `pagination-partial-last/` | `page_size=2`, страница 2 неполная — конец выборки | конец определяется по `len < page_size`; не подтверждено |
| `pagination-empty-last/` | `page_size=2`, объектов поровну на страницы — страница 3 пустая | страница за концом — `[]`, не 404; не подтверждено |
| `same-ver/` | два чтения подряд: та же `ver` и те же `attrs` → no-op | `ver` растёт при каждом изменении `attrs`; не подтверждено |
| `ref-change/` | `ver` 1 → 2, ссылка `platform` меняется `[3101, 3102]` → `[3101, 3103]` | ссылка лежит в `attrs` стороны-источника связи; не подтверждено |
| `deferred-reference/` | система ссылается на решение 7299, которого нет в списке решений → `DeferRelation` | — |
| `tombstone/` | объект 3102 есть в snapshot 1 и пропал в snapshot 2 → tombstone | архивный объект исчезает из основного списка; не подтверждено |
| `same-name/` | два разных `id` с одинаковым `name` | — |
