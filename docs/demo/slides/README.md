# Презентация Arch RAG (DEMO.7)

Beamer-презентация по плану [`../presentation-plan.md`](../presentation-plan.md). Преамбула владельца лежит в `template/00-preamble.tex` без правок; название, подзаголовок, дата и заголовок оглавления переопределены в `main.tex` после `\input`. PDF в Git не хранится.

## Сборка

```bash
pip install latexminted          # minted v3; нужен рядом с TeX Live (pdflatex, latexmk, beamer, tikz, minted)
./make-slides.sh                 # main.pdf (показ) и main-notes.pdf (заметки на втором экране справа)
```

Структура: `main.tex` (преамбула, переопределения, `\input` секций), `sections/NN-*.tex` (по файлу на блок плана 1–8, вводная и вопросы), `main-notes.tex` (тонкая обёртка: `\shownotes` + `\input{main}`).

| Скрипт | Что делает |
|---|---|
| `make-slides.sh` | собирает логотип и оба PDF; падает на Overfull box в `main.pdf` |
| `make-listings.sh` | пересоздаёт `listings/*.json` из `platform/docker/demo/expected/` (сокращение до ключевых полей, значения не правятся; запускать при смене эталонов) |
| `make-figures.sh` | пересъёмка `figures/*.png` из `graph.html` (S-01…S-05 и X-* для слайдов), headless Chrome/Chromium (`CHROME=...`), стенд не нужен |

Иллюстрации и листинги коммитятся. PDF не коммитится (корневой `.gitignore`).

## Скриншоты, которые кладут вручную

Если файл есть, слайд показывает его, иначе сокращённый листинг из `expected/` (`\ifshot`) или ничего. Имена в `figures/`:

| Файл | Где используется |
|---|---|
| `S-06-agent-sc1.png` | сценарий 1 |
| `S-08-agent-footprint.png` | сценарии 2 и 3 |
| `S-09-agent-impact.png` | сценарий 4 |
| `S-10-agent-redeploy.png` | сценарий 8 |
| `S-11-mcp-connected.png` | отдельный слайд в блоке 5 |
| `S-12-access-script.png` | сценарий 7 (вместо таблицы) |
| `S-13-inspector-tools.png` | «Шесть read-only tools» |
| `S-14-inspector-call.png` | сценарий 7 (вместо ошибки maxDepth) |
| `S-15-console-journal.png`, `S-16-console-identity.png` | отдельный слайд после сценария 10 |

S-07 (раскрытый вызов `search_assets`) слайдом не занят. Не снимать окна с токеном и `.env`.

## CI

Job `slides` лежит в `ci-job-slides.yml`, его нужно вставить в `.github/workflows/ci.yml` (у токена агента нет scope `workflow`). Job собирает PDF в контейнере `texlive/texlive` и публикует артефакт `arch-rag-slides`. Не проверен в CI.
