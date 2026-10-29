#!/usr/bin/env sh
# Compila la webview Angular de nexo-vscode y copia el resultado al plugin.
# Uso (desde traza-eclipse): ./tools/sync-webview.sh
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NEXO="$(cd "$ROOT/.." && pwd)/nexo-vscode"
DEST="$ROOT/bundles/com.ftejero21.traza/webview"

npm --prefix "$NEXO/webview" run build
[ -f "$NEXO/dist/webview/index.html" ] || { echo "No se encontro $NEXO/dist/webview/index.html" >&2; exit 1; }

rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$NEXO/dist/webview/." "$DEST/"
echo "Webview sincronizada en $DEST"
