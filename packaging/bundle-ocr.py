#!/usr/bin/env python3
"""Bundle a matching Homebrew Tesseract and its dylibs without absolute loader paths."""
from pathlib import Path
import shutil
import subprocess
import sys
import json
import re

source = Path(sys.argv[1]).resolve()
destination = Path(sys.argv[2])
language_dir = Path(sys.argv[3])
architecture = sys.argv[4]
dependencies = {}
minimum_macos = (13, 0, 0)
queue = [source]
while queue:
    library = queue.pop()
    if library in dependencies:
        continue
    architectures = subprocess.check_output(["lipo", "-archs", str(library)], text=True).split()
    if architecture not in architectures:
        raise RuntimeError(f"OCR 架构不匹配：{library.name}，需要 {architecture}")
    build_info = subprocess.check_output(["vtool", "-show-build", str(library)], text=True)
    for version in re.findall(r"minos\s+([0-9.]+)", build_info):
        parts = tuple(int(part) for part in version.split("."))
        minimum_macos = max(minimum_macos, (parts + (0, 0, 0))[:3])
    links = []
    for line in subprocess.check_output(["otool", "-L", str(library)], text=True).splitlines()[1:]:
        original = line.strip().split(" (")[0]
        if original.startswith(("/usr/lib/", "/System/Library/")):
            continue
        if original.startswith("@rpath/"):
            name = original.removeprefix("@rpath/")
            candidates = [library.parent / name, library.parent.parent / "lib" / name]
            resolved = next((path.resolve() for path in candidates if path.exists()), None)
            if resolved is None:
                raise RuntimeError(f"无法解析 OCR 依赖：{original}")
        elif original.startswith("@loader_path/"):
            resolved = (library.parent / original.removeprefix("@loader_path/")).resolve()
        elif original.startswith("/"):
            resolved = Path(original).resolve()
        else:
            raise RuntimeError(f"尚未支持的 OCR 依赖路径：{original}")
        links.append((original, resolved))
        if resolved != library:
            queue.append(resolved)
    dependencies[library] = links

names = [path.name for path in dependencies]
if len(names) != len(set(names)):
    raise RuntimeError("OCR 依赖文件名冲突")
destination.mkdir(parents=True, exist_ok=True)
(destination / "lib").mkdir()
(destination / "tessdata").mkdir()
(destination / "licenses").mkdir()
for language in ("chi_sim", "chi_tra", "eng"):
    shutil.copy2(language_dir / f"{language}.traineddata", destination / "tessdata")

for library, links in dependencies.items():
    target = destination / "tesseract" if library == source else destination / "lib" / library.name
    shutil.copy2(library, target)
    target.chmod(0o755)
    changes = []
    if library != source:
        changes += ["-id", "@loader_path/" + library.name]
    for original, resolved in links:
        relative = "@loader_path/lib/" if library == source else "@loader_path/"
        changes += ["-change", original, relative + resolved.name]
    if changes:
        subprocess.run(["install_name_tool", *changes, str(target)], check=True, capture_output=True)
    subprocess.run(["codesign", "--force", "--sign", "-", str(target)], check=True, capture_output=True)
    # Preserve notices shipped with each dependency's installed formula.
    cellar_root = library.parent.parent
    if library == source:
        cellar_root = source.parent.parent
    for notice in cellar_root.iterdir():
        if notice.is_file() and any(token in notice.name.upper() for token in ("LICENSE", "COPYING", "NOTICE", "AUTHORS")):
            shutil.copy2(notice, destination / "licenses" / f"{cellar_root.parent.name}-{notice.name}")

shutil.copy2(source.parent.parent / "LICENSE", destination / "licenses" / "tesseract-LICENSE")
(destination / "licenses" / "language-data-NOTICE.txt").write_text(
    "Tesseract language data: chi_sim, chi_tra, eng. Apache License 2.0.\n"
    "https://github.com/tesseract-ocr/tessdata\n"
    "See tesseract-LICENSE for the license text.\n"
)
(destination / "metadata.json").write_text(json.dumps({
    "minimum_macos": ".".join(map(str, minimum_macos)),
    "architecture": architecture,
}))
print(f"已内置本地 OCR：{len(dependencies)} 个二进制文件，中文简繁体及英文模型")
