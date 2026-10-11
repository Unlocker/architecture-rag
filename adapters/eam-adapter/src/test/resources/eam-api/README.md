# Fixtures EAM API

Ответы EAM в формате `docs/eam-api.yaml` («EAM Tool API» v0.4): тела `GET /api/<type>/` (голый массив `ArchObject[]`) и
метаданные типов. **Доступа к реальному EAM нет**, поэтому это не записанные ответы, а собранные по спеке данные.
Всё, чего нет в спеке, — допущение; владелец подтвердил его 2026-10-10 и 2026-10-11: имена типов (`platforms`, `itsystems`, `itproducts` как «ИТ-решение»),
id типов, поля `attrs` (`name`, `status`, `lifecycle`, `criticality`, `description`, `eam_id`), одиночные ссылки `itsystems.product_id`, `itsystems.platform_id` и `itproducts.platform_id` (платформа системы совпадает с платформой её решения), формат `options`. Обоснование и полный список — раздел «Решения» в
`docs/06-eam-integration.md`. Когда появится доступ к EAM, fixtures перегенерируются записью реальных ответов.

| Каталог | Сценарий («Тестирование» в `06-eam-integration.md`) | Допущения |
|---|---|---|
| `types.json`, `options/` | метаданные типов: `/api/types/`, `/api/types/{id}/options/?kind=fields\|system` | формат `options`, id типов, кратность ссылок |
| `baseline/` | полный срез `ITSystem → Solution → Platform`, совпадает с seed заглушки `EamApiSeed` | `eam_id` = `systemCode` SCM |
| `pagination-partial-last/` | `page_size=2`, страница 2 неполная — конец выборки | конец определяется по `len < page_size` |
| `pagination-empty-last/` | `page_size=2`, объектов поровну на страницы — страница 3 пустая | страница за концом — `[]`, не 404 |
| `same-ver/` | два чтения подряд: та же `ver` и те же `attrs` → no-op | `ver` растёт при каждом изменении `attrs` |
| `ref-change/` | `ver` 1 → 2, `platform_id` решения меняется `3102` → `3103` | одиночная ссылка (решение 2026-10-11) |
| `deferred-reference/` | система ссылается на решение 7299, которого нет в списке решений → `DeferRelation` | — |
| `tombstone/` | объект 3102 есть в snapshot 1 и пропал в snapshot 2 → tombstone | архивный объект исчезает из основного списка |
| `same-name/` | два разных `id` с одинаковым `name` | — |
| `platform-mismatch/` | система (`platform_id` 3102) ссылается на решение, чья `platform_id` 3101 → `Conflict`, граф строится по решению | инвариант «платформа системы = платформа решения» |
