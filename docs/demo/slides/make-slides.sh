#!/usr/bin/env bash
# Повторяемая сборка презентации с чистого checkout:
#   out/slides.pdf        — для показа;
#   out/slides-notes.pdf  — для докладчика: после каждого слайда страница с заметками.
# Нужны: TeX Live (pdflatex, latexmk, beamer, tikz, minted), latexminted (pip install latexminted), jq.
# Кадры graph.html (images/generated) подкладывает make-figures.sh; без них слайды собираются с заглушками.
#   SKIP_FIGURES=1 — не снимать кадры (в CI они приходят артефактом отдельного job).
set -euo pipefail
cd "$(dirname "$0")"

for t in pdflatex latexmk latexminted jq; do
  command -v "$t" >/dev/null 2>&1 || { echo "не найден $t (latexminted: pip install latexminted)" >&2; exit 1; }
done

mkdir -p build out images/generated

# логотип Arch RAG: TikZ -> PDF
if [ ! -f images/archrag-logo.pdf ] || [ images/archrag-logo.tex -nt images/archrag-logo.pdf ]; then
  (cd images && pdflatex -interaction=nonstopmode -halt-on-error -output-directory ../build archrag-logo.tex >/dev/null \
    && cp ../build/archrag-logo.pdf archrag-logo.pdf)
fi

./make-snippets.sh
if [ -z "${SKIP_FIGURES:-}" ] && ! ls images/generated/*.png >/dev/null 2>&1; then
  ./make-figures.sh || echo "кадры graph.html не сняты, слайды получат заглушки" >&2
fi

build_one() { # build_one <каталог сборки> <имя pdf> <pretex>
  rm -rf "build/$1"; mkdir -p "build/$1"
  TEXMF_OUTPUT_DIRECTORY="build/$1" latexmk -pdf -shell-escape -interaction=nonstopmode -halt-on-error \
    -outdir="build/$1" -usepretex="$3" main.tex
  cp "build/$1/main.pdf" "out/$2.pdf"
}

build_one show slides ''
build_one notes slides-notes '\def\withnotes{1}'

# Overfull на слайдах не допускается (Underfull не считается: он не виден на слайде).
# Для сборки с заметками это только предупреждение: длинная заметка может занять больше страницы.
if grep -a -E '^Overfull \\[hv]box' build/show/main.log; then
  echo "есть Overfull box на слайдах, см. выше" >&2
  exit 1
fi
if grep -a -q -E '^Overfull \\vbox' build/notes/main.log; then
  echo "предупреждение: заметки на некоторых страницах не помещаются" >&2
fi
echo "готово: out/slides.pdf, out/slides-notes.pdf"
