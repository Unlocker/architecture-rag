# git-url-normalization — Требования

> Сценарий модуля [`canonical-model`](../requirements.md). `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`, `CT/` = `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/`. Дизайн — [`design.md`](design.md).

## Обзор

`GitUrls.normalize(String)` приводит любую допустимую запись URL Git-репозитория к канонической строке. Канон служит ключом сопоставления репозиториев: deploy map находит репозиторий SCM по равенству строк (`ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/ScmMapper.java:100-108`, `DeployMapMapper.java:182`), а `Deployment.repositoryUrl` обязан быть уже нормализован (`CM/node/Deployment.java:30-32`) 🟢.

## Правила

- Результат: `<scheme>://<host>[:port]/<path>` 🟢 (`CM/node/GitUrls.java:72-73`).
- Схема: `https`, `ssh`, `git` и scp-форма `[user@]host:path` → `https`; `http` → `http`; регистр схемы входа не важен; любая иная (`ftp`, `file`, отсутствие) → IAE `"unsupported git url scheme"` 🟢 (`:53-58`).
- Хост — в нижнем регистре; пустой/`null` → IAE `"git url has no host"` 🟢 (`:59, 65-67, 72`).
- Путь — как есть (регистр сохраняется), вложенные группы сохраняются; убираются ведущие `/`, хвостовые `/`, суффикс `.git`, снова хвостовые `/`; пустой → IAE `"git url has no repository path"` 🟢 (`:68-71, 76-91`).
- Порт сохраняется только для `http` и `https` (если задан); для `ssh`/`git`/scp — отбрасывается 🟢 (`:61-63`).
- Учётные данные (`user:pw@`, `token@`) в результате отсутствуют; сообщения исключений и `cause` не содержат вход 🟢 (`:49-52`).
- Входная строка предварительно `strip()` 🟢 (`:34`); `null`/blank → IAE `"git url is blank"` (`:31-33`).
- scp-распознавание: `value` не содержит `://` и соответствует `^(?:[^@/\s]+@)?([^:/\s]+):(?!//)(.+)$` 🟢 (`:20, 40-44`).
- Идемпотентность: `normalize(normalize(x)) == normalize(x)` 🟢 (`CT/node/GitUrlsTest.java:102-108`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|----|------------|-----------|------------------|
| RF-01 | 14 вариантов записи одного репозитория дают `https://git.example.org/pay/payments-api` (с/без `.git`, `/`, верхний регистр схемы/хоста, `user:secret@`, `token@`, scp, `ssh://`, `ssh://…:2222`, `git://`, пробелы вокруг) 🟢 | Must | `GitUrlsTest.normalizesToCanonicalHttps` (`:34`) |
| RF-02 | `http` остаётся `http`: `http://Git.Example.org/pay/payments-api.git` → `http://git.example.org/pay/payments-api` 🟢 | Must | `httpStaysHttp` (`:39`) |
| RF-03 | Регистр пути сохраняется 🟢 | Must | `pathKeepsCase` (`:45`) |
| RF-04 | Порт `https://host:8443/…` сохраняется; порт `ssh://…:2222` отбрасывается 🟢 | Must | `httpPortIsKeptAndSshPortDropped` (`:51`) |
| RF-05 | Вложенные группы `grp/sub/api` сохраняются 🟢 | Should | `nestedGroupsArePreserved` (`:57`) |
| RF-06 | Учётные данные не попадают ни в результат, ни в сообщение; при синтаксической ошибке нет `cause` 🟢 | Must | `credentialsNeverAppearInResultOrError` (`:63`), `syntaxErrorDoesNotLeakInputThroughMessageOrCause` (`:71`) |
| RF-07 | Отказ (IAE) для `null`, `""`, `"   "`, `garbage`, `not a url`, `ftp://…`, `file:///…`, `https://host`, `https://host/`, `https://host/.git`, `git@host:`, `https:///pay/api` 🟢 | Must | `rejectsBlankOrUnrecognized` (`:93`) |
| RF-08 | Идемпотентность 🟢 | Must | `isIdempotent` (`:102`) |

## Нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Входной URL с секретом не попадает в исключение (ловушка 12 CLAUDE.md) | `CM/node/GitUrls.java:49-52`; `CT/node/GitUrlsTest.java:63-76` | 🟢 |
| Детерминизм | Чистая функция без I/O и состояния; `Locale.ROOT` для регистра | `CM/node/GitUrls.java:53, 72` | 🟢 |

## Критерии приёмки

```gherkin
Дано "git@GIT.EXAMPLE.ORG:pay/payments-api.git"
Когда GitUrls.normalize
Тогда "https://git.example.org/pay/payments-api"
# CT/node/GitUrlsTest.java:34

Дано "https://git.example.org:8443/pay/api.git"
Когда GitUrls.normalize
Тогда "https://git.example.org:8443/pay/api"
# CT/node/GitUrlsTest.java:51

Дано "ftp://u:pw123@git.example.org/a/b"
Когда GitUrls.normalize
Тогда IllegalArgumentException, сообщение без "pw123"
# CT/node/GitUrlsTest.java:63

Дано "https://u:pw123@git.example.org/a b" (пробел — синтаксическая ошибка URI)
Когда GitUrls.normalize
Тогда IllegalArgumentException "unrecognized git url", getCause() == null
# CT/node/GitUrlsTest.java:71

Дано "https://git.example.org/.git"
Когда GitUrls.normalize
Тогда IllegalArgumentException (пустой путь после отрезания .git)
# CT/node/GitUrlsTest.java:93
```

## MoSCoW

| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-01, RF-06, RF-07, RF-08 | Must | Ключ сопоставления репозиториев, безопасность |
| RF-02…RF-05 | Must/Should | Нормализация деталей URL |
| Единый нормализатор с `FeatureNormalizer.repositoryUrl` | Won't (пока) | TODO в `ScmMapper.java:103-104`; функции различаются (нижний регистр у `FeatureNormalizer`) 🟡 |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `CM/node/GitUrls.java` | `normalize`, `trimPath`, `SCP` | 🟢 |
| `CT/node/GitUrlsTest.java` | 9 тестовых методов | 🟢 |
