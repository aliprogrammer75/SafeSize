#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
cd "$TEMP"
npm pack klinecharts@9.8.12 --ignore-scripts --registry=https://registry.npmjs.org --silent
tar -xzf klinecharts-9.8.12.tgz
DEST="$ROOT/app/src/main/assets/chart"
mkdir -p "$DEST"
cp package/dist/umd/klinecharts.min.js "$DEST/klinecharts.min.js"
cp package/LICENSE "$DEST/KLineChart-LICENSE.txt"
if [[ -f package/NOTICE ]]; then cp package/NOTICE "$DEST/KLineChart-NOTICE.txt"; fi
if [[ -d package/licenses ]]; then cp -R package/licenses "$DEST/"; fi
test -s "$DEST/klinecharts.min.js"
