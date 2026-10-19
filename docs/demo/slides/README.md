# Презентация Arch RAG (DEMO.7)

Beamer-презентация по плану [`../presentation-plan.md`](../presentation-plan.md). Преамбула владельца лежит в `template/00-preamble.tex` без правок; название, подзаголовок, дата и заголовок оглавления переопределены в `main.tex` после `\input`. PDF в Git не хранится.

## Сборка

```bash
pip install latexminted          # minted v3; нужен рядом с TeX Live (pdflatex, latexmk, beamer, tikz, minted)
./make-slides.sh                 # out/slides.pdf (показ) и out/slides-notes.pdf (заметки докладчика)
```

Зависимости скрипта: `pdflatex`, `latexmk`, `latexminted`, `jq`; для кадров `make-figures.sh` ещё Chrome или Chromium (`CHROME=...`).

| Скрипт | Что делает |
|---|---|
| `make-slides.sh` | собирает логотип, фрагменты, обе версии PDF; падает на Overfull box |
| `make-snippets.sh` | сокращает `platform/docker/demo/expected/*.json` до ключевых полей (`build/snippets`) |
| `make-figures.sh` | кадры `docs/demo/graph.html` по сценариям (`images/generated`); стенд не нужен |

Версии PDF: `slides.pdf` без заметок; `slides-notes.pdf` с заметками докладчика (`\note{}`) отдельной страницей после каждого слайда.

Кадры консоли (S-15, S-16) и ответы агента (S-06…S-11) в сборку не входят: они требуют поднятого стенда, входа через Keycloak и живого Claude Code; снимаются на репетиции по [п. 5 плана](../presentation-plan.md#5-скриншоты-которые-нужно-снять).

## CI

Job'ы лежат в `slides-workflow.yml`: у токена агента нет scope `workflow`, поэтому файл не подключён. Чтобы включить, его копируют как `.github/workflows/slides.yml`. Job `figures` снимает кадры в headless Chrome, job `slides` собирает PDF в контейнере `texlive/texlive` (`pip install latexminted`) и публикует артефакт `arch-rag-slides`. Сборка этого job в CI не проверялась.
