#!/bin/bash
set -euo pipefail
CAST_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
source "$CAST_ROOT/scripts/environment.sh"
CAST_ARCH="$(cast_architecture "${1:-$(uname -m)}")"
CAST_APP_NAME="鸿蒙投屏"
if [[ "$CAST_ARCH" == "intel" ]]; then CAST_APP_NAME="鸿蒙投屏-Intel"; fi
CAST_SOURCE="$CAST_ROOT/dist/$CAST_APP_NAME.app"
if [[ ! -d "$CAST_SOURCE/Contents" ]]; then
    echo "找不到已编译的 App：$CAST_SOURCE" >&2
    exit 1
fi
CAST_DESKTOP="$HOME/Desktop"
mkdir -p "$CAST_DESKTOP"
CAST_COPY_STAGING=$(mktemp -d "$CAST_DESKTOP/.harmony-cast-sync.XXXXXX")
trap 'rm -rf -- "$CAST_COPY_STAGING"' EXIT
# Copy completely before replacing the previous desktop App or shortcut.
ditto "$CAST_SOURCE" "$CAST_COPY_STAGING/$CAST_APP_NAME.app"
CAST_DESTINATION="$CAST_DESKTOP/$CAST_APP_NAME.app"
if [[ -e "$CAST_DESTINATION" || -L "$CAST_DESTINATION" ]]; then
    mkdir -p "$CAST_ROOT/dist/backups"
    mv "$CAST_DESTINATION" "$CAST_ROOT/dist/backups/desktop-$CAST_APP_NAME-$(date +%Y%m%d-%H%M%S)-$$.app"
fi
mv "$CAST_COPY_STAGING/$CAST_APP_NAME.app" "$CAST_DESTINATION"
# File Provider adds desktop metadata; verify signed code and resources normally.
codesign --verify --deep "$CAST_DESTINATION"
echo "已同步到桌面：$CAST_DESTINATION"
