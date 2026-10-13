# contracts/source-spi — решения

| Решение | Evidence | ADR / docs | Уверенность |
|---|---|---|---|
| Мастер-системы в PoC — заглушки, поэтому контракт чтения и формат webhook определены самим проектом, а не внешним API (кроме EAM) | `SPI/package-info.java:1-4` | ADR 0010 `docs/adr/0010-master-systems-are-stubs.md`; `docs/06-eam-integration.md` | 🟡 |
| Webhook несёт только уведомление; состояние читается `fetchById` у источника (подписанное тело не источник истины) | `SPI/WebhookEvent.java:5-7` | `docs/03-interfaces.md` §3 | 🟢 |
| Удаление только после `snapshotComplete` на последней странице полного прохода | `SPI/ChangePage.java:8-9, 22-24` | ADR 0020 `docs/adr/0020-tombstone-instead-of-hard-delete.md`; CLAUDE.md, ловушка 9 | 🟢 |
| `PARTIAL` не затирает известные поля, `null` в payload = «не передано» | `SPI/Completeness.java:3-6`, `SPI/SourceChange.java:14, 39-41` | CLAUDE.md, ловушка 5 | 🟢 |
| HMAC-SHA256 над `timestamp + "." + body` с replay window | `SPI/WebhookSignature.java:15-20` | `docs/03-interfaces.md:347` | 🟢 |
| Сам SPI не знает размер replay window: параметр `window` приходит от вызывающей стороны (`AdapterConfig`, по умолчанию 5 минут по `docs/03-interfaces.md:347`) | `SPI/WebhookSignature.java:56, 58-64` | — | 🟡 |
