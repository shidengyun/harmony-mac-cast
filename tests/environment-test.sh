#!/bin/bash
set -euo pipefail
CAST_SOURCE="$(cd -- "$(dirname -- "$0")/.." && pwd)"
CAST_TEST_DIR=$(mktemp -d)
trap 'rm -r -- "$CAST_TEST_DIR"' EXIT
CAST_ROOT="$CAST_TEST_DIR/project with spaces"
mkdir -p "$CAST_ROOT/vendor/arm64/jdk/Contents/Home/bin" \
    "$CAST_ROOT/vendor/intel/jdk/Contents/Home/bin" \
    "$CAST_ROOT/vendor/arm64/toolchains" "$CAST_ROOT/vendor/intel/toolchains" \
    "$CAST_ROOT/vendor/arm64/ocr"
for arch in arm64 intel; do
    touch "$CAST_ROOT/vendor/$arch/jdk/Contents/Home/bin/java" \
        "$CAST_ROOT/vendor/$arch/toolchains/hdc"
    chmod +x "$CAST_ROOT/vendor/$arch/jdk/Contents/Home/bin/java" \
        "$CAST_ROOT/vendor/$arch/toolchains/hdc"
done
touch "$CAST_ROOT/vendor/arm64/ocr/tesseract"
chmod +x "$CAST_ROOT/vendor/arm64/ocr/tesseract"
source "$CAST_SOURCE/scripts/environment.sh"
cd "$CAST_TEST_DIR"
check() {
    if [[ "$1" != "$2" ]]; then
        printf 'FAIL: expected <%s>, got <%s>\n' "$2" "$1" >&2
        exit 1
    fi
}
check "$(cast_absolute_path vendor/arm64/toolchains/hdc)" "$CAST_ROOT/vendor/arm64/toolchains/hdc"
check "$(cast_absolute_path "$CAST_ROOT/build")" "$CAST_ROOT/build"
check "$(cast_architecture x86_64)" intel
for arch in arm64 intel; do
    check "$(cast_jdk '' "$arch")" "$CAST_ROOT/vendor/$arch/jdk/Contents/Home"
    check "$(cast_toolchains '' "$arch")" "$CAST_ROOT/vendor/$arch/toolchains"
done
check "$(cast_jdk custom/jdk intel)" "$CAST_ROOT/custom/jdk"
check "$(cast_toolchains custom/tools arm64)" "$CAST_ROOT/custom/tools"
check "$(cast_tesseract arm64)" "$CAST_ROOT/vendor/arm64/ocr/tesseract"
check "$(CAST_TESSERACT_PATH=custom/ocr cast_tesseract intel)" "$CAST_ROOT/custom/ocr"
if cast_architecture invalid >/dev/null 2>&1; then
    echo 'FAIL: unsupported architecture accepted' >&2
    exit 1
fi
echo 'PASS: relocated paths with spaces, both architectures, relative overrides, invalid architecture'
