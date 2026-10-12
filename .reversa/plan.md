# Plano de Exploração — architecture-rag

> Criado pelo Reversa em 2026-10-12
> Marque cada tarefa com ✅ quando concluída.
> Você pode editar este plano antes de iniciar: adicione, remova ou reordene tarefas conforme necessário.

---

## Fase 1: Reconhecimento 🔍

- [x] ✅ **Scout** — Mapeamento de estrutura de pastas e tecnologias
- [x] ✅ **Scout** — Análise de dependências e gerenciadores de pacotes
- [x] ✅ **Scout** — Identificação de entry points, CI/CD e configurações

## Soul Extractor ✅

- [x] ✅ **Soul Extractor** — `_reversa_sdd/soul.md` (R.1, UNLOCKER-262)

## Decisão de organização das specs 🗂️

> Entre o Scout e o Arqueólogo, o Reversa pergunta como você quer organizar as specs (por módulo, caso de uso, endpoint, híbrida, por features ou customizada). A escolha fica persistida em `.reversa/config.toml` na seção `[specs]` e não será reperguntada em execuções futuras. Para reapresentar o menu, remova manualmente a seção.

## Fase 2: Escavação 🏗️

> Переписано после Scout (R.1, UNLOCKER-262): по одному пункту Archaeologist на каждый из 17 модулей. Группы соответствуют подзадачам R.2a–R.2e. `platform/docker` — не Maven-модуль, входит в R.2e отдельным пунктом.

### R.2a contracts (UNLOCKER-263)

- [x] ✅ **Archaeologist** — `contracts/canonical-model`: узлы, связи, `GraphCommand`, `AuthorityMatrix`, инварианты
- [x] ✅ **Archaeologist** — `contracts/event-schemas`: `CanonicalEvent`, `EventJournal`, `Checkpoint`, `ProcessingStatus`
- [x] ✅ **Archaeologist** — `contracts/source-spi`: `SourceConnector`, `SourceChange`, вебхуки

### R.2b adapters (UNLOCKER-264)

- [x] ✅ **Archaeologist** — `adapters/adapter-core`: `Poller`, `WebhookServer`, `InboxWriter`, `RetryPolicy`
- [x] ✅ **Archaeologist** — `adapters/eam-adapter`: адаптер EAM
- [x] ✅ **Archaeologist** — `adapters/scm-adapter`: адаптер Git/SCM
- [x] ✅ **Archaeologist** — `adapters/asset-adapter`: адаптер материальных активов
- [x] ✅ **Archaeologist** — `adapters/deploymap-adapter`: адаптер deploy map / Helm

### R.2c ingestion (UNLOCKER-265)

- [x] ✅ **Archaeologist** — `ingestion/event-journal`: Postgres-журнал, S3 raw store
- [x] ✅ **Archaeologist** — `ingestion/normalizer`: мапперы источников → `GraphCommand`
- [x] ✅ **Archaeologist** — `ingestion/identity-resolution`: crosswalk, кандидаты, конфликты
- [x] ✅ **Archaeologist** — `ingestion/graph-projector`: проекция в Neo4j, reconcile, `Neo4jSchema`
- [x] ✅ **Archaeologist** — `ingestion/ingestion-service`: диспетчер журнала, админ-эндпоинт, replay/rebuild

### R.2d access (UNLOCKER-266)

- [x] ✅ **Archaeologist** — `access/graph-query-core`: шаблоны запросов, лимиты, executor
- [x] ✅ **Archaeologist** — `access/mcp-server`: MCP tools, безопасность, аудит
- [x] ✅ **Archaeologist** — `access/admin-console`: read-only консоль: REST и безопасность
- [ ] **Archaeologist** — `access/admin-ui`: SPA React/TypeScript

### R.2e platform (UNLOCKER-267)

- [ ] **Archaeologist** — `platform/source-stubs`: заглушки мастер-систем
- [ ] **Archaeologist** — `platform/demo-launcher`: запуск адаптеров и заглушек
- [ ] **Archaeologist** — `platform/integration-tests`: IT-набор, фикстуры, приёмочные сценарии
- [ ] **Archaeologist** — `platform/docker (не модуль)`: compose, Dockerfile, Keycloak, observability, скрипты

## Fase 3: Interpretação 🧠

- [ ] **Detetive** — Arqueologia Git e ADRs retroativos
- [ ] **Detetive** — Regras de negócio implícitas e máquinas de estado
- [ ] **Detetive** — Matriz de permissões (RBAC/ACL)
- [ ] **Arquiteto** — Diagramas C4 (Contexto, Containers, Componentes)
- [ ] **Arquiteto** — ERD completo e integrações externas
- [ ] **Arquiteto** — Spec Impact Matrix

## Fase 4: Geração 📝

- [ ] **Redator** — Specs SDD por componente
- [ ] **Redator** — OpenAPI (se aplicável)
- [ ] **Redator** — User Stories (se aplicável)
- [ ] **Redator** — Code/Spec Matrix

## Fase 5: Revisão ✅

- [ ] **Revisor** — Revisão cruzada de specs
- [ ] **Revisor** — Resolução de lacunas com o usuário
- [ ] **Revisor** — Relatório de confiança final

---

## Agentes Independentes

> Execute estes agentes quando os recursos estiverem disponíveis — podem rodar em qualquer fase.

- [ ] **Visor** — Análise de interface via screenshots
- [ ] **Data Master** — Análise completa do banco de dados
- [ ] **Design System** — Extração de tokens de design
- [ ] **Tracer** — Análise dinâmica (requer sistema acessível)

---

## Próximo passo

Após o Time de Descoberta concluir e o `_reversa_sdd/` estar populado, você pode disparar um dos fluxos seguintes:

- `/reversa-migrate`: orquestrador do **Time de Migração** (Paradigm Advisor → Curator → Strategist → Designer → Screen Translator → Inspector). Gera as specs do sistema novo. Saída em `_reversa_sdd/migration/` e `_reversa_sdd/screens/`.
- `/reversa-reconstructor`: gera plano bottom-up para reimplementar o software a partir das specs do legado (uma tarefa por sessão).
