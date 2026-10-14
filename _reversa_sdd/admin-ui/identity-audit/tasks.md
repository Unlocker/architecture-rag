# identity-audit: задачи

## Pré-requisitos
- [ ] Общий каркас ([`../tasks.md`](../tasks.md) T-03..T-07); REST `/api/identity/**` и `/api/audit`

## Tarefas

- [ ] T-01: `Conflicts` (селектор статуса без «все», таблица, вид «свойство»/«связь», пагинация)
  - Origin: `access/admin-ui/src/pages/sync/IdentityTab.tsx:9-61`
  - Критерий готовности: AC-01, AC-02, AC-03
  - Уверенность: 🟢
- [ ] T-02: `Candidates` (селектор статуса, совпавшие признаки `feature=value`, score)
  - Origin: `access/admin-ui/src/pages/sync/IdentityTab.tsx:63-103`
  - Критерий готовности: AC-01, AC-03
  - Уверенность: 🟢 (поведение) / 🟢 (`RESOLVED` = закрыт подтверждением оператора, 🟢 (реализовано — UNLOCKER-282), Q-03)
- [ ] T-03: `IdentityTab` (композиция секций)
  - Origin: `access/admin-ui/src/pages/sync/IdentityTab.tsx:106-113`
  - Критерий готовности: обе секции загружаются независимо
  - Уверенность: 🟢
- [ ] T-04: `AuditTab` (колонки, «обрезано», пустое состояние, пагинация)
  - Origin: `access/admin-ui/src/pages/sync/AuditTab.tsx:7-51`
  - Критерий готовности: AC-04, AC-05, AC-06
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `access/admin-ui/src/pages/SyncPage.test.tsx` — 5 тестов (Identity, фильтр RESOLVED, Аудит ×3)
- [ ] TT-02: 🟡 нет отдельного теста ошибки загрузки для вкладки «Аудит» и для Identity (покрыт общим `useAsync`)
- [ ] TT-03: тест связи UI с реальным жизненным циклом кандидата (Q-03) не добавлен; сам жизненный цикл 🟢 (реализовано — UNLOCKER-282) покрыт `CandidateApprovalIT`

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-03 (🟢 закрыт, UNLOCKER-282); Q-15 (🟡).
