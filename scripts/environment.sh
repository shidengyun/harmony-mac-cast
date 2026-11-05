#!/bin/bash
# Shared by source builds and the packaged App. Relative overrides use CAST_ROOT.
cast_absolute_path() {
    case "$1" in
        /*) printf '%s\n' "$1" ;;
        *) printf '%s/%s\n' "$CAST_ROOT" "$1" ;;
    esac
}

cast_architecture() {
    case "${1:-$(uname -m)}" in
        arm64) printf 'arm64\n' ;;
        intel|x86_64) printf 'intel\n' ;;
        *) echo "支持的架构：arm64 / intel" >&2; return 1 ;;
    esac
}

cast_jdk() {
    local configured="${1:-}" arch="${2:-$(cast_architecture)}" candidate
    if [[ -n "$configured" ]]; then
        cast_absolute_path "$configured"
        return
    fi
    candidate="$CAST_ROOT/vendor/$arch/jdk/Contents/Home"
    if [[ -x "$candidate/bin/java" ]]; then
        printf '%s\n' "$candidate"
        return
    fi
    # Only a native build may use an installed JDK; cross builds need a local JDK.
    if [[ "$arch" == "$(cast_architecture)" ]]; then
        if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
            cast_absolute_path "$JAVA_HOME"
            return
        fi
        candidate=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    fi
    printf '%s\n' "$CAST_ROOT/vendor/$arch/jdk/Contents/Home"
}

cast_toolchains() {
    local configured="${1:-}" arch="${2:-$(cast_architecture)}" binary
    if [[ -n "$configured" ]]; then
        cast_absolute_path "$configured"
        return
    fi
    if [[ -x "$CAST_ROOT/vendor/$arch/toolchains/hdc" ]]; then
        printf '%s\n' "$CAST_ROOT/vendor/$arch/toolchains"
        return
    fi
    if [[ "$arch" == "$(cast_architecture)" ]]; then
        binary=$(command -v hdc || true)
        if [[ -n "$binary" ]]; then
            dirname -- "$(cast_absolute_path "$binary")"
            return
        fi
    fi
    printf '%s\n' "$CAST_ROOT/vendor/$arch/toolchains"
}

cast_tesseract() {
    local arch="$1" candidate
    if [[ -n "${CAST_TESSERACT_PATH:-}" ]]; then
        cast_absolute_path "$CAST_TESSERACT_PATH"
    elif [[ -x "$CAST_ROOT/vendor/$arch/ocr/tesseract" ]]; then
        printf '%s\n' "$CAST_ROOT/vendor/$arch/ocr/tesseract"
    elif [[ "$arch" == "$(cast_architecture)" ]]; then
        candidate=$(command -v tesseract || true)
        [[ -z "$candidate" ]] || cast_absolute_path "$candidate"
    fi
    return 0
}
