#!/bin/bash
set -euo pipefail
CAST_PROJECT="$(cd -- "$(dirname -- "$0")" && pwd)"
CAST_ROOT="$CAST_PROJECT"
source "$CAST_ROOT/scripts/environment.sh"
CAST_BUILD_JDK="$(cast_jdk "${CAST_BUILD_JDK_PATH:-}")"
CAST_DEP_ARCH="$(cast_architecture "${1:-$(uname -m)}")"
CAST_ARCH="$CAST_DEP_ARCH"
CAST_JDK="$(cast_jdk "${CAST_RUNTIME_JDK_PATH:-}" "$CAST_DEP_ARCH")"
CAST_TOOLS="$(cast_toolchains "${CAST_TOOLCHAINS_PATH:-}" "$CAST_DEP_ARCH")"
CAST_TESSERACT="$(cast_tesseract "$CAST_DEP_ARCH")"
CAST_TESSDATA=""
if [[ -n "${CAST_TESSDATA_PATH:-}" ]]; then
    CAST_TESSDATA="$(cast_absolute_path "$CAST_TESSDATA_PATH")"
elif [[ -x "$CAST_TESSERACT" ]]; then
    CAST_TESSDATA="$CAST_ROOT/vendor/$CAST_DEP_ARCH/ocr/tessdata"
    if [[ ! -d "$CAST_TESSDATA" ]]; then
        CAST_TESSDATA=$("$CAST_TESSERACT" --list-langs 2>/dev/null | sed -n 's/^List of available languages in "\(.*\)".*$/\1/p')
    fi
fi
case "$CAST_ARCH" in
    arm64)
        CAST_APP_NAME="鸿蒙投屏"
        CAST_FFMPEG_ARCH="macosx-arm64"
        ;;
    x86_64|intel)
        CAST_ARCH="x86_64"
        CAST_APP_NAME="鸿蒙投屏-Intel"
        CAST_FFMPEG_ARCH="macosx-x86_64"
        ;;
    *) echo "支持的架构：arm64 / intel"; exit 1 ;;
esac
if [[ ! -d "$CAST_JDK/jmods" || ! -x "$CAST_JDK/bin/jlink" || ! -x "$CAST_TOOLS/hdc" || ! -f "$CAST_TOOLS/libusb_shared.dylib" ]]; then
    echo "缺少目标架构的 Java JDK 或鸿蒙 toolchains：$CAST_ARCH"
    exit 1
fi
CAST_STAGING=$(mktemp -d "$CAST_PROJECT/.app-build.XXXXXX")
CAST_APP="$CAST_STAGING/$CAST_APP_NAME.app"
CAST_RESOURCES="$CAST_APP/Contents/Resources"
mkdir -p "$CAST_APP/Contents/MacOS" "$CAST_RESOURCES/app/build" "$CAST_RESOURCES/tools" "$CAST_PROJECT/dist"
cp "$CAST_PROJECT/packaging/Info.plist" "$CAST_APP/Contents/Info.plist"
if [[ "$CAST_ARCH" == "x86_64" ]]; then
    /usr/libexec/PlistBuddy -c 'Set :CFBundleIdentifier local.harmonycast.mac.intel' "$CAST_APP/Contents/Info.plist"
fi
CAST_BUILD_JDK_PATH="$CAST_BUILD_JDK" "$CAST_PROJECT/build.sh"
cp -R "$CAST_PROJECT/build/." "$CAST_RESOURCES/app/build/"
mkdir -p "$CAST_RESOURCES/app/vendor/release/HOScrcpy/libs"
for jar in "$CAST_PROJECT/vendor/release/HOScrcpy/libs/"*.jar; do
    case "$jar" in
        *windows-x86_64*|*hosScrcpy-1.0.15*) continue ;;
        *macosx-*) [[ "$jar" == *"$CAST_FFMPEG_ARCH"* ]] || continue ;;
    esac
    cp "$jar" "$CAST_RESOURCES/app/vendor/release/HOScrcpy/libs/"
done
cp "$CAST_PROJECT/vendor/hosScrcpy-1.0.18-beta.jar" "$CAST_RESOURCES/app/vendor/"
cp "$CAST_PROJECT/启动鸿蒙投屏.command" "$CAST_PROJECT/wireless-device.txt" "$CAST_RESOURCES/app/"
mkdir -p "$CAST_RESOURCES/app/scripts"
cp "$CAST_PROJECT/scripts/environment.sh" "$CAST_RESOURCES/app/scripts/"
cp "$CAST_TOOLS/hdc" "$CAST_TOOLS/libusb_shared.dylib" "$CAST_RESOURCES/tools/"
if [[ -f "$CAST_TOOLS/NOTICE.txt" ]]; then cp "$CAST_TOOLS/NOTICE.txt" "$CAST_RESOURCES/tools/"; fi
echo "正在打包 Java 运行环境…"
CAST_MODULES="java.base,java.compiler,java.desktop,java.management,java.naming,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.httpserver"
if [[ -f "$CAST_JDK/jmods/org.graalvm.sdk.jmod" ]]; then CAST_MODULES="$CAST_MODULES,org.graalvm.sdk"; fi
"$CAST_JDK/bin/jlink" --add-modules "$CAST_MODULES" \
    --strip-debug --no-header-files --no-man-pages --compress=2 --output "$CAST_RESOURCES/runtime"
echo "正在生成 App 图标与启动器…"
swift "$CAST_PROJECT/packaging/GenerateIcon.swift" "$CAST_STAGING/AppIcon.iconset"
iconutil -c icns "$CAST_STAGING/AppIcon.iconset" -o "$CAST_RESOURCES/AppIcon.icns"
swiftc -O -target "$CAST_ARCH-apple-macos13.0" "$CAST_PROJECT/packaging/Launcher.swift" -o "$CAST_APP/Contents/MacOS/HarmonyCast"
swiftc -O -target "$CAST_ARCH-apple-macos13.0" "$CAST_PROJECT/packaging/ScreenTextOCR.swift" -o "$CAST_RESOURCES/tools/ScreenTextOCR"
if [[ -x "$CAST_TESSERACT" ]]; then
    python3 "$CAST_PROJECT/packaging/bundle-ocr.py" "$CAST_TESSERACT" "$CAST_RESOURCES/tools/ocr" "$CAST_TESSDATA" "$CAST_ARCH"
else
    echo "未提供对应架构的 Tesseract，图片识别使用系统 Vision 引擎。"
fi
codesign --force --deep --sign - "$CAST_APP"
if [[ -e "$CAST_PROJECT/dist/$CAST_APP_NAME.app" ]]; then
    mkdir -p "$CAST_PROJECT/dist/backups"
    mv "$CAST_PROJECT/dist/$CAST_APP_NAME.app" "$CAST_PROJECT/dist/backups/$CAST_APP_NAME-$(date +%Y%m%d-%H%M%S).app"
fi
mv "$CAST_APP" "$CAST_PROJECT/dist/$CAST_APP_NAME.app"
echo "已生成：$CAST_PROJECT/dist/$CAST_APP_NAME.app"
bash "$CAST_PROJECT/scripts/sync-desktop-app.sh" "$CAST_DEP_ARCH"
