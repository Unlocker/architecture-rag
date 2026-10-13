# git-url-normalization — Задачи

Общий дизайн — [`../design.md`](../design.md). `CM/` и `CT/` — как в [`../requirements.md`](../requirements.md).

## Пререквизиты
- [ ] JDK 21.

## Задачи

- [ ] T-01 — `normalize`: проверка blank, `strip`.
  - Origin: `CM/node/GitUrls.java:31-34`
  - Критерий готовности: `null`, `""`, `"   "` → IAE `"git url is blank"`.
  - Уверенность: 🟢
- [ ] T-02 — scp-ветка по `SCP` при отсутствии `://`.
  - Origin: `CM/node/GitUrls.java:20, 40-44`
  - Критерий готовности: `git@host:org/repo.git` → `https://host/org/repo`.
  - Уверенность: 🟢
- [ ] T-03 — URI-ветка: `URISyntaxException` → IAE без cause; `switch` схем; порт только http(s).
  - Origin: `CM/node/GitUrls.java:45-64`
  - Критерий готовности: `ssh://…:2222` без порта; `ftp://` → IAE без значений в сообщении.
  - Уверенность: 🟢
- [ ] T-04 — Проверка хоста, `trimPath`, сборка результата.
  - Origin: `CM/node/GitUrls.java:65-91`
  - Критерий готовности: `https://host/.git` → IAE `no repository path`; идемпотентность.
  - Уверенность: 🟢

## Тесты

- [ ] TT-01 — параметризованный список из 14 эквивалентных записей: есть (`GitUrlsTest.normalizesToCanonicalHttps`).
- [ ] TT-02 — `http`, регистр пути, порт, вложенные группы: есть.
- [ ] TT-03 — утечка секретов в сообщении и `cause`: есть.
- [ ] TT-04 — отказы (`null`, пусто, мусор, `ftp`, `file`, пустой путь/хост): есть.
- [ ] TT-05 — идемпотентность: есть.
- [ ] TT-06 — хост с `_` и URL с портом без пути: отсутствует 🟡.

## Порядок
T-01 → T-02 → T-03 → T-04.

## Открытые 🔴
- Нужно ли объединить с `FeatureNormalizer.repositoryUrl` (TODO в `ScmMapper.java:103-104`)? Решение за архитектором.
