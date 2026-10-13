# keycloak-oidc: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §4, §5, §8. Блок-схема потока токенов: [`../../flowcharts/docker.md`](../../flowcharts/docker.md) §2. Права: [`../../permissions.md`](../../permissions.md) §2, §5, §8. Клиентская сторона входа: [`../../admin-ui/oidc-login/requirements.md`](../../admin-ui/oidc-login/requirements.md).

## Visão Geral

Keycloak (realm `archrag`, `start-dev`, импорт realm при первом старте) выдаёт три вида токенов: агентский (клиент `archrag-demo`, `client_credentials`, scope `architecture.read`, audience MCP), оператора admin REST (тот же клиент со scope `architecture.admin`, дополнительно audience `urn:archrag:admin`) и консоли (клиент `archrag-admin-console`, Authorization Code + PKCE, scope `architecture.admin`, audiences консоли и админки, `azp` консоли). Потребители проверяют issuer, audience, scope и (ingestion) `azp` 🟢 (`platform/docker/keycloak/archrag-realm.json:5-111`; `platform/docker/docker-compose.yml:137-175`).

## Regras de Negócio

- Realm `archrag`, `sslRequired: none`; импортируется только при создании контейнера (`start-dev --import-realm`, файл смонтирован в `/opt/keycloak/data/import`); подстановки `${…}` в JSON берутся из переменных окружения контейнера Keycloak 🟢 (`platform/docker/keycloak/archrag-realm.json:2-4`; `platform/docker/docker-compose.yml:139-157`; `platform/docker/README.md:127`).
- `KC_HOSTNAME=http://keycloak:8080`: `iss` токена всегда `http://keycloak:8080/realms/archrag`, независимо от того, через какой адрес (напрямую или proxy) токен запрошен; совпадает с `ARCHRAG_OIDC_ISSUER_URI` всех сервисов 🟢 (`platform/docker/docker-compose.yml:143-145, 195, 231, 282`; `platform/docker/README.md:88-89`).
- Client scope `architecture.read` без mapper'ов; `architecture.admin` содержит audience-mapper `admin-audience`: в access token добавляется audience `${ARCHRAG_ADMIN_RESOURCE_URI}` (`urn:archrag:admin`), в ID token — нет 🟢 (`platform/docker/keycloak/archrag-realm.json:6-28`; `platform/docker/docker-compose.yml:151`).
- Клиент `archrag-demo`: confidential, секрет из `KEYCLOAK_CLIENT_SECRET`, `serviceAccountsEnabled`, `standardFlowEnabled=false`, `directAccessGrantsEnabled=false`; default scope — `architecture.read`; optional — `architecture.admin` (запрашивается параметром `scope`); mapper `mcp-audience` добавляет audience `${ARCHRAG_MCP_RESOURCE_URI}` (`https://localhost:<ARCHRAG_PUBLIC_PORT>/mcp`) 🟢 (`platform/docker/keycloak/archrag-realm.json:50-75`; `platform/docker/docker-compose.yml:150`).
- Клиент `archrag-admin-console`: public, `standardFlowEnabled`, без direct grants, implicit и service accounts; PKCE S256 (`pkce.code.challenge.method`); `redirectUris` и `webOrigins` — только `http://127.0.0.1:${ARCHRAG_CONSOLE_PORT}`; нет default scope'ов, optional — `architecture.admin`, `architecture.read`; mapper `console-audience` (`${ARCHRAG_CONSOLE_RESOURCE_URI}` = `urn:archrag:console`) и mapper `sub` 🟢 (`platform/docker/keycloak/archrag-realm.json:76-111`; `platform/docker/docker-compose.yml:152-153`).
- Демо-пользователь консоли: имя и пароль из `KEYCLOAK_CONSOLE_USER`/`KEYCLOAK_CONSOLE_PASSWORD` (пароль не временный), `emailVerified`, e-mail `<имя>@archrag.invalid`, realm-роль `archrag-console-admin` 🟢 (`platform/docker/keycloak/archrag-realm.json:35-48`).
- Роль `archrag-console-admin` нигде не проверяется: токен консоли получает любой пользователь realm; принято владельцем как ограничение PoC (П-9) 🟡 (Q-04).
- `ingestion` admin REST принимает токен, только если audience = `urn:archrag:admin`, есть scope `architecture.admin` и `azp ∈ ARCHRAG_ADMIN_ALLOWED_CLIENTS` (по умолчанию `archrag-demo`): токен консоли отвергается по `azp` 🟢 (`platform/docker/README.md:91-92, 125`; `platform/docker/console-smoke.sh:85-95`).
- `mcp-server` принимает токен с audience = `ARCHRAG_MCP_RESOURCE_URI`; scope tool'а проверяется только для `tools/call`; `admin-console` — audience `urn:archrag:console` и scope `architecture.admin` на `/api/**` 🟢 (`platform/docker/docker-compose.yml:232, 283`; `_reversa_sdd/permissions.md` §3, §5).
- Токен-endpoint realm доступен через proxy (`/realms/archrag/protocol/openid-connect/`), админка Keycloak через proxy не открывается; на loopback Keycloak публикуется целиком (включая master-админку) 🟢 (`platform/docker/proxy/nginx.conf:35-40`; `platform/docker/docker-compose.yml:158-161`).
- Скрипты получают admin-токен клиентом `archrag-demo` со `scope=architecture.admin` и вызывают admin REST изнутри контейнера `ingestion`; секрет клиента передаётся через stdin контейнера 🟢 (`platform/docker/scripts/lib.sh:19, 98-119`).
- Состояние Keycloak (`start-dev`, встроенная БД) не вынесено в том: пользователи и сессии теряются при пересоздании контейнера 🟡 (Q-20).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Токен агента: audience MCP, scope `architecture.read` | Must | AC-01 |
| RF-02 | Токен оператора: scope `architecture.admin` → audience `urn:archrag:admin` | Must | AC-02, AC-03 |
| RF-03 | Токен консоли: Code + PKCE, audience консоли и админки, `azp` консоли | Must | AC-04 |
| RF-04 | Токен консоли не проходит в admin REST ingestion | Must | AC-05 |
| RF-05 | Публичный клиент консоли без direct grants | Must | AC-06 |
| RF-06 | Фиксированный issuer независимо от адреса запроса | Must | AC-07 |
| RF-07 | Realm импортируется один раз; правки требуют пересоздания | Should | AC-08 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|------|-----------|----------|-----------|
| Безопасность | Секреты клиента и пароль пользователя подставляются из окружения контейнера, в JSON значений нет | `platform/docker/keycloak/archrag-realm.json:44, 56` | 🟢 (но переменные лежат в `environment` — Q-09) |
| Безопасность | Redirect URI клиента консоли ограничен loopback и портом консоли | `platform/docker/keycloak/archrag-realm.json:87-88` | 🟢 |
| Безопасность | Режим `start-dev`, `sslRequired: none`, bootstrap-админ — только для демо | `platform/docker/docker-compose.yml:139-142`; `platform/docker/keycloak/archrag-realm.json:4`; gap 2.1 | 🟡 |
| Надёжность | Healthcheck Keycloak: `/health/ready` на management-порту 9000 через `/dev/tcp` (в образе нет `curl`); `start_period 60s`, до 60 попыток | `platform/docker/docker-compose.yml:164-175` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (platform/docker/smoke.sh:21-25)
Дано клиент archrag-demo и его секрет
Когда POST https://localhost:<порт>/realms/archrag/protocol/openid-connect/token, grant_type=client_credentials
Тогда токен содержит audience MCP и scope architecture.read; POST /mcp с ним = 200

# AC-02 (platform/docker/smoke.sh:78-79, негатив)
Дано токен archrag-demo со scope по умолчанию (architecture.read)
Когда POST /admin/replay на ingestion
Тогда 401 (в токене нет audience urn:archrag:admin)

# AC-03 (platform/docker/smoke.sh:76-77)
Дано токен archrag-demo, запрошенный с scope=architecture.admin
Когда POST /admin/replay изнутри ingestion
Тогда 200

# AC-04 (platform/docker/console-smoke.sh:47-51, 73-83, 85-88)
Дано демо-пользователь консоли
Когда вход Authorization Code + PKCE S256 со scope "openid architecture.admin" на redirect http://127.0.0.1:<порт консоли>/
Тогда токен содержит аудиторию urn:archrag:admin (и консоли) и azp = archrag-admin-console; GET /api/graph/stats = 200

# AC-05 (platform/docker/console-smoke.sh:89-95, негатив)
Дано токен консоли с аудиторией админки и scope architecture.admin
Когда POST /admin/replay на ingestion
Тогда 401 (azp не входит в ARCHRAG_ADMIN_ALLOWED_CLIENTS)

# AC-06 (platform/docker/console-smoke.sh:53-56, негатив)
Дано публичный клиент консоли
Когда grant_type=password с логином и паролем демо-пользователя
Тогда 400 или 401 (direct access grants выключены)

# AC-07 (platform/docker/README.md:88-89)
Дано токен запрошен напрямую у keycloak:8080 и через proxy
Когда сравниваются iss
Тогда оба равны http://keycloak:8080/realms/archrag

# AC-08 (platform/docker/README.md:127)
Дано realm уже импортирован
Когда изменён archrag-realm.json без пересоздания контейнера
Тогда изменения не применяются; после docker compose up -d --force-recreate keycloak — применяются
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-06 | Must | Критерии 6 PoC, ADR 0026 |
| RF-07 | Should | Операционная особенность Keycloak |
| Ролевая проверка `archrag-console-admin` | Won't | Принято владельцем как ограничение (П-9) |
| Внешний IdP, ротация секретов, отзыв токенов | Won't | gap 2.1 |

## Rastreabilidade de Código

| Файл | Назначение | Покрытие |
|------|------------|----------|
| `platform/docker/keycloak/archrag-realm.json` | scope'ы, роль, пользователь, клиенты | 🟢 |
| `platform/docker/docker-compose.yml` | сервис `keycloak`, audience-переменные | 🟢 |
| `platform/docker/proxy/nginx.conf` | публикация token endpoint | 🟢 |
| `platform/docker/scripts/lib.sh` | выдача admin-токена в скриптах | 🟢 |
