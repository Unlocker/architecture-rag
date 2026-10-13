# graph-explorer: задачи

## Pré-requisitos
- [ ] Общий каркас из [`../tasks.md`](../tasks.md) (T-03..T-08); `cytoscape` 3.30.4 (`access/admin-ui/package.json:12`)
- [ ] REST консоли `/api/graph/**` доступен с токеном `architecture.admin`

## Tarefas

- [ ] T-01: Сводка графа (`GraphSummary`: две таблицы, «Последнее обновление», «Обновить», пустое/ошибка)
  - Origin: `access/admin-ui/src/pages/graph/GraphSummary.tsx:7-61`
  - Критерий готовности: AC-01
  - Уверенность: 🟢
- [ ] T-02: Поиск (`AssetSearch`: `trim`, пустой запрос не отправляется, чекбоксы `ASSET_TYPES`, `truncated`, защита от гонки)
  - Origin: `access/admin-ui/src/pages/graph/AssetSearch.tsx:10-92`
  - Критерий готовности: AC-02, AC-03
  - Уверенность: 🟢
- [ ] T-03: Карточка актива (`AssetCard`, `isStale`, свойства через `text()`, жизненный цикл, происхождение с tombstone и расхождениями)
  - Origin: `access/admin-ui/src/pages/graph/AssetCard.tsx:9-13, 31-121`
  - Критерий готовности: AC-04
  - Уверенность: 🟢
- [ ] T-04: Блок «Исходный код» для `Deployment` (7 символов + полный commit, URL текстом)
  - Origin: `access/admin-ui/src/pages/graph/AssetCard.tsx:15-28`
  - Критерий готовности: AC-05
  - Уверенность: 🟢
- [ ] T-05: Окрестность (`NeighborhoodView`: глубина 1/2, чекбоксы `RELATION_TYPES`, баннер `truncated`)
  - Origin: `access/admin-ui/src/pages/graph/NeighborhoodView.tsx:10-52`
  - Критерий готовности: AC-06, AC-07
  - Уверенность: 🟢
- [ ] T-06: `GraphCanvas` (Cytoscape `cose`, цвет по метке, центр с рамкой, `opacity 0.5` у закрытых, tap → `onSelect`, `destroy` при размонтировании)
  - Origin: `access/admin-ui/src/components/GraphCanvas.tsx:10-69`
  - Критерий готовности: AC-08
  - Уверенность: 🟢
- [ ] T-07: `GraphPage` (композиция и выбранный `gid`)
  - Origin: `access/admin-ui/src/pages/GraphPage.tsx:8-23`
  - Критерий готовности: клик по узлу или результату меняет карточку и окрестность
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `access/admin-ui/src/pages/GraphPage.test.tsx` — 17 тестов (сводка, поиск, карточка, Deployment, окрестность, гонка); Cytoscape замокан
- [ ] TT-02: 🟢 [R.8: ответ владельца, вопрос 9] нет теста синхронности `ASSET_TYPES`/`RELATION_TYPES` с бэкендом (Q-05)
- [ ] TT-03: 🟡 нет теста отрисовки Cytoscape в реальном браузере под CSP (Q-10)

## Ordem Sugerida
T-06 → T-05 → T-03 → T-04 → T-02 → T-01 → T-07.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-04, Q-05 (🟢 [R.8: ответ владельца, вопрос 9]); Q-10, Q-16 (🟡).
