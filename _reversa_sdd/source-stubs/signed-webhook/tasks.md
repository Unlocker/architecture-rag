# signed-webhook: задачи

## Pré-requisitos
- [ ] `WebhookSignature.sign` и константы заголовков; `WebhookEvent` ([`../../source-spi/tasks.md`](../../source-spi/tasks.md)); `Json.write` ([`../tasks.md`](../tasks.md) T-02)

## Tarefas

- [ ] T-01: тело уведомления (`body`) — семь ключей в фиксированном порядке, без payload
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:58-69`
  - Критерий готовности: AC-01 (тело не содержит состояния объекта)
  - Уверенность: 🟢
- [ ] T-02: `send`, `sendAt`, `sendWithBadSignature`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:39-56`
  - Критерий готовности: AC-01, AC-02, AC-03
  - Уверенность: 🟢
- [ ] T-03: `post` — заголовки, таймауты, возврат статуса, обработка `IOException`/`InterruptedException`, `close`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:21-37, 71-95`
  - Критерий готовности: AC-04, AC-05; секрет не появляется в сообщении исключения
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `StubWebhookSenderTest` — 4 теста с локальным HTTP-приёмником, проверяющим `WebhookSignature.verify` (окно 5 минут)
  - Origin: `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSenderTest.java:32-90`
  - Критерий готовности: surefire зелёный без Docker
  - Уверенность: 🟢
- [ ] TT-02: 🟡 тест на недоступный target (`IllegalStateException` без секрета)
  - Origin: AC-05
  - Критерий готовности: сообщение содержит только имя класса исключения
  - Уверенность: 🟡

## Ordem Sugerida
T-01 → T-02 → T-03.

## Lacunas Pendentes (🔴)
Нет собственных 🔴; расхождение кода источника — Q-06 (🟡) в [`../questions.md`](../questions.md).
