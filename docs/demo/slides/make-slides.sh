#!/usr/bin/env bash
# Повторяемая сборка презентации с чистого checkout:
#   main.pdf        — для показа;
#   main-notes.pdf  — для докладчика (заметки на втором экране справа).
# Нужны: TeX Live (pdflatex, latexmk, beamer, tikz, minted), latexminted (pip install latexminted).
# Иллюстрации (figures/*.png) и листинги (listings/*.json) лежат в Git; их пересъёмка — make-figures.sh и make-listings.sh.
set -euo pipefail
cd "$(dirname "$0")"

for t in pdflatex latexmk latexminted; do
  command -v "$t" >/dev/null 2>&1 || { echo "не найден $t (latexminted: pip install latexminted)" >&2; exit 1; }
done

# логотип Arch RAG: TikZ -> PDF (images/archrag-logo.pdf лежит в Git, пересобирается при правке .tex)
if [ images/archrag-logo.tex -nt images/archrag-logo.pdf ]; then
  mkdir -p build
  (cd images && pdflatex -interaction=nonstopmode -halt-on-error -output-directory ../build archrag-logo.tex >/dev/null \
    && cp ../build/archrag-logo.pdf archrag-logo.pdf)
fi

build_one() { # build_one <имя без .tex>
  rm -rf "build/$1"; mkdir -p "build/$1"
  # TEXMF_OUTPUT_DIRECTORY нужен minted v3 при отдельном outdir
  TEXMF_OUTPUT_DIRECTORY="build/$1" latexmk -pdf -shell-escape -interaction=nonstopmode -halt-on-error \
    -outdir="build/$1" "$1.tex" >"build/$1.log" 2>&1 || { tail -40 "build/$1.log" >&2; return 1; }
  cp "build/$1/$1.pdf" "$1.pdf"
}

mkdir -p build
build_one main & p1=$!
build_one main-notes & p2=$!
wait $p1 && wait $p2

# Overfull на слайдах не допускается (Underfull не виден на слайде).
# Для main-notes это только предупреждение: длинная заметка может не поместиться в область второго экрана.
if grep -a -E '^Overfull \\[hv]box' build/main/main.log; then
  echo "есть Overfull box на слайдах, см. выше" >&2
  exit 1
fi
if grep -a -q -E '^Overfull \\[hv]box' build/main-notes/main-notes.log; then
  echo "предупреждение: в main-notes.pdf есть Overfull (заметки)" >&2
fi
echo "готово: main.pdf, main-notes.pdf"
