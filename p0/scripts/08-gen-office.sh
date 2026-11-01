#!/usr/bin/env bash
# 生成 office 类语料（docx/xlsx/pptx/pdf）与一份无文字层扫描件。
# 依赖与门户参考解析器同源：POI 5.4.1 + PDFBox 3.0.x + 本仓库已缓存的离线 jar，不联网。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
APP="$ROOT/portal-poc"
GEN="$HERE/corpus-gen"
OUT="$ROOT/.tmp/corpusgen-classes"

[[ -s "$APP/cp.txt" ]] || (cd "$APP" && mvn -B -o -q dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=runtime)
CP="$(cat "$APP/cp.txt")"

mkdir -p "$OUT"
OUTW="$(cygpath -w "$OUT")"
javac -encoding UTF-8 -proc:none -nowarn -cp "$CP" -d "$OUTW" "$GEN/CorpusOfficeGen.java"

SPEC="$(cygpath -w "$ROOT/evaluation/corpus-src/office-spec.json")"
CORPUS="$(cygpath -w "$ROOT/evaluation/corpus")"
FONT="${P0_CORPUS_FONT:-C:/Windows/Fonts/simhei.ttf}"

java -Dfile.encoding=UTF-8 -cp "$CP;$OUTW" CorpusOfficeGen "$SPEC" "$CORPUS" "$FONT"
python "$HERE/08-gen-corpus.py" manifest
