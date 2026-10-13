# git-url-normalization — Дизайн

> Общий дизайн — [`../design.md`](../design.md). `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`.

## Интерфейс

| Символ | Сигнатура | Примечание |
|---|---|---|
| `GitUrls.normalize` | `static String normalize(String raw)` (`CM/node/GitUrls.java:30`) | чистая функция; IAE на всех ошибках |
| `GitUrls.SCP` | `Pattern ^(?:[^@/\s]+@)?([^:/\s]+):(?!//)(.+)$` (`:20`) | группа 1 — host, группа 2 — path |
| `GitUrls.trimPath` | `private static String` (`:76-91`) | |

## Основной поток

1. `raw == null || raw.isBlank()` → IAE `"git url is blank"` (`:31-33`); `value = raw.strip()` (`:34`).
2. Если `value` без `"://"` и совпал с `SCP` → `scheme = "https"`, `host = group(1)`, `path = group(2)`, порт не задаётся (`:40-44`).
3. Иначе `new URI(value)`; `URISyntaxException` → IAE `"unrecognized git url"` БЕЗ `cause` (`:46-52`).
4. `s = uri.getScheme()` в нижнем регистре (или `""`); `switch`: `https|ssh|git` → `https`; `http` → `http`; иначе IAE `"unsupported git url scheme"` (`:53-58`).
5. `host = uri.getHost()`, `path = uri.getRawPath()`; `port = uri.getPort()` только при `s ∈ {http, https}` (`:59-63`).
6. `host == null || host.isBlank()` → IAE `"git url has no host"` (`:65-67`).
7. `path = trimPath(path)`: срез ведущих `/`; срез хвостовых `/`; срез `.git`; срез хвостовых `/` (`:76-91`); пустой → IAE `"git url has no repository path"` (`:68-71`).
8. Результат: `scheme + "://" + host.toLowerCase(ROOT) + (port >= 0 ? ":" + port : "") + "/" + path` (`:72-73`).

```mermaid
flowchart TD
  A[raw] --> B{null или blank?}
  B -- да --> X1[IAE: git url is blank]
  B -- нет --> C{нет '://' и совпал scp?}
  C -- да --> D[scheme=https, host и path из scp]
  C -- нет --> E[new URI]
  E -- ошибка --> X2[IAE: unrecognized git url, без cause]
  E --> F{схема}
  F -- https/ssh/git --> G[scheme=https]
  F -- http --> H[scheme=http]
  F -- иная --> X3[IAE: unsupported scheme]
  G --> I[port только для http/https]
  H --> I
  D --> J{host пуст?}
  I --> J
  J -- да --> X4[IAE: no host]
  J -- нет --> K[trimPath]
  K --> L{path пуст?}
  L -- да --> X5[IAE: no repository path]
  L -- нет --> M[scheme://host[:port]/path]
```

## Альтернативные потоки

- `https://user:secret@host/...` — `uri.getHost()` не содержит `user:secret`, результат без учётных данных (`CT/node/GitUrlsTest.java:24-25`).
- `ssh://git@host:2222/...` — порт отбрасывается (`CM/node/GitUrls.java:61-63`).
- `HTTPS://HOST/...` — схема и хост в нижнем регистре (`:53, 72`).
- Сообщение «scheme» не содержит значения схемы, поэтому секреты в ней не утекут (`:57`).

## Зависимости

`java.net.URI`, `java.util.regex` — JDK. Внутренние потребители: `Deployment` (инвариант идемпотентности, `CM/node/Deployment.java:30-32`). Внешние: `ScmMapper.java:108`, `DeployMapMapper.java:182` (`ingestion/normalizer/`), `DeploymentLookup` (Javadoc `:38-40`).

## Решения

| Решение | Evidence | Уверенность |
|---|---|---|
| Схемы `ssh`/`git`/scp → `https` для единого ключа | `CM/node/GitUrls.java:12-14, 55` | 🟢 |
| Путь сохраняет регистр | `:11, 68` | 🟢 |
| Без `cause` у IAE, чтобы вход не утёк в журнал | `:49-51` | 🟢 |
| Порт только у http(s) | `:61-63` | 🟢 |
| Идемпотентность — инвариант для `Deployment.repositoryUrl` | `CM/node/Deployment.java:30-32` | 🟢 |

## Риски

- 🟡 `FeatureNormalizer.repositoryUrl` (identity-resolution) использует другую нормализацию (нижний регистр, без схемы) — TODO в `ingestion/normalizer/.../ScmMapper.java:103-104`; один и тот же URL даёт разные ключи алиаса и признака (spec-impact-matrix §2 № 11).
- 🟡 Хосты с символами, при которых `URI.getHost()` возвращает `null` (например, `_` в имени), дают IAE `"git url has no host"` — вывод по коду `URI`, тестов нет.
- 🟡 Код файла отформатирован с 2 пробелами (остальной модуль — 4) — несущественно.
