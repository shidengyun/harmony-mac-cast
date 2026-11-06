#!/bin/bash
set -euo pipefail
CAST_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
CAST_TEST_APP="${1:-$CAST_ROOT/dist/鸿蒙投屏-Intel.app}"
CAST_TEST_TOOLS="$CAST_TEST_APP/Contents/Resources/tools"
CAST_TEST_TEMP=$(mktemp -d /tmp/harmony-intel-ocr.XXXXXX)
trap 'rm -r -- "$CAST_TEST_TEMP"' EXIT
"$CAST_TEST_TOOLS/ocr/tesseract" --version
"$CAST_TEST_TOOLS/ocr/tesseract" --tessdata-dir "$CAST_TEST_TOOLS/ocr/tessdata" --list-langs > "$CAST_TEST_TEMP/languages"
for language in chi_sim chi_tra eng; do
    rg -qx "$language" "$CAST_TEST_TEMP/languages"
done
CAST_TEST_HELPER="$CAST_TEST_TOOLS/ScreenTextOCR"
if [[ "${2:-}" == --source-helper ]]; then
    mkdir "$CAST_TEST_TEMP/tools"
    ln -s "$CAST_TEST_TOOLS/ocr" "$CAST_TEST_TEMP/tools/ocr"
    swiftc -O -target x86_64-apple-macos13.0 "$CAST_ROOT/packaging/ScreenTextOCR.swift" -o "$CAST_TEST_TEMP/tools/ScreenTextOCR"
    CAST_TEST_HELPER="$CAST_TEST_TEMP/tools/ScreenTextOCR"
fi
cat > "$CAST_TEST_TEMP/sample.swift" <<'SWIFT'
import AppKit
let image = NSImage(size: NSSize(width: 1200, height: 500))
image.lockFocus()
NSColor.white.setFill()
NSRect(x: 0, y: 0, width: 1200, height: 500).fill()
let attributes: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 40), .foregroundColor: NSColor.black]
let lines = ["Hello Harmony 12345", "中文识别测试 鸿蒙手机投屏", "繁體文字識別 螢幕畫面", "选择手机连接 点击开始投屏", "文字识别结果 保存截图"]
for (index, line) in lines.enumerated() {
    line.draw(at: NSPoint(x: 50, y: 420 - index * 80), withAttributes: attributes)
}
image.unlockFocus()
let bitmap = NSBitmapImageRep(data: image.tiffRepresentation!)!
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
SWIFT
swift "$CAST_TEST_TEMP/sample.swift" "$CAST_TEST_TEMP/sample.png"
env -u CAST_TESSERACT_PATH -u CAST_TESSDATA_PATH "$CAST_TEST_HELPER" "$CAST_TEST_TEMP/sample.png" > "$CAST_TEST_TEMP/result.json"
python3 - "$CAST_TEST_TEMP/result.json" "$CAST_TEST_TOOLS/ocr" <<'PY'
import json
from pathlib import Path
import subprocess
import sys
text = json.loads(Path(sys.argv[1]).read_text())["text"]
assert "Harmony" in text and "12345" in text and "中文" in text and "繁體" in text, text
root = Path(sys.argv[2])
assert json.loads((root / "metadata.json").read_text())["architecture"] == "x86_64"
for binary in [root / "tesseract", *sorted((root / "lib").glob("*.dylib"))]:
    assert "x86_64" in subprocess.check_output(["lipo", "-archs", str(binary)], text=True).split(), binary
    for line in subprocess.check_output(["otool", "-L", str(binary)], text=True).splitlines()[1:]:
        library = line.strip().split(" (")[0]
        assert library.startswith(("@loader_path/", "/usr/lib/", "/System/Library/")), library
print(text)
print("PASS: bundled Intel OCR, all three language models, local dylibs, and /tmp image recognition")
PY
