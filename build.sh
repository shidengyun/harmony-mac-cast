#!/bin/bash
set -euo pipefail
CAST_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
source "$CAST_ROOT/scripts/environment.sh"
CAST_BUILD_JDK="$(cast_jdk "${CAST_BUILD_JDK_PATH:-}")"
if [[ ! -x "$CAST_BUILD_JDK/bin/javac" ]]; then
    echo "缺少 Java 17 JDK，请按 README 配置 vendor/<架构>/jdk/ 或 CAST_BUILD_JDK_PATH。" >&2
    exit 1
fi
cd "$CAST_ROOT"
mkdir -p build
"$CAST_BUILD_JDK/bin/javac" --release 17 \
    -cp 'build:vendor/hosScrcpy-1.0.18-beta.jar:vendor/release/HOScrcpy/libs/*' \
    -d build AutoStart.java CastAppearance.java PhoneKeyboard.java FileDropTransfer.java \
    HdcFileSender.java CaptureRecovery.java CaptureConnection.java ScreenText.java HdcCommand.java SidebarShortcuts.java \
    vendor/patches/utils/HdcUtil.java
swiftc -O packaging/ScreenTextOCR.swift -o build/ScreenTextOCR
echo "编译完成：build/"
