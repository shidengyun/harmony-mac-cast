#!/usr/bin/env python3
"""Install verified Intel Homebrew OCR bottles into the project's private vendor tree."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.parse
import urllib.request

VERSION = "5.5.1"
TAG = "ventura"
TESSERACT_SHA256 = "3f17c42eb79554bf6544344380fd67e22ecc54aed07e719b4f7f03c4fb6f83c7"
ROOT = Path(__file__).resolve().parents[1]


class Bottles:
    def __init__(self, prefix, cache, proxy):
        self.prefix, self.cache = prefix, cache
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler(
            {"http": proxy, "https": proxy} if proxy else None))
        self.tokens, self.locked, self.installed, self.manifest = {}, {}, {}, []

    def request(self, url, headers=None):
        return self.opener.open(urllib.request.Request(url, headers=headers or {}), timeout=30)

    def repository(self, name):
        return "homebrew/core/" + name.replace("@", "/")

    def headers(self, name):
        repo = self.repository(name)
        if repo not in self.tokens:
            query = urllib.parse.urlencode({"service": "ghcr.io", "scope": f"repository:{repo}:pull"})
            with self.request("https://ghcr.io/token?" + query) as response:
                self.tokens[repo] = json.load(response)["token"]
        return {"Authorization": "Bearer " + self.tokens[repo],
                "Accept": "application/vnd.oci.image.index.v1+json"}

    def select(self, name, version):
        url = f"https://ghcr.io/v2/{self.repository(name)}/manifests/{version}"
        with self.request(url, self.headers(name)) as response:
            index = json.load(response)
        candidates = [item for item in index["manifests"] if
                      item.get("platform", {}).get("architecture") == "amd64"
                      and item.get("platform", {}).get("os") == "darwin"
                      and item.get("annotations", {}).get("org.opencontainers.image.ref.name", "").endswith("." + TAG)]
        if not candidates:
            raise RuntimeError(f"找不到 {name} {version} 的 Intel macOS {TAG} 包")
        return candidates[0]

    def install(self, name, version=None):
        if name in self.installed:
            return self.installed[name]
        if version is None:
            dependency = self.locked.get(name)
            if dependency is None:
                raise RuntimeError(f"依赖不在官方运行时清单中：{name}")
            version = dependency["pkg_version"]
        selected = self.select(name, version)
        annotations = selected["annotations"]
        digest = annotations["sh.brew.bottle.digest"]
        if name == "tesseract" and digest != TESSERACT_SHA256:
            raise RuntimeError("Tesseract 官方包校验值与固定版本不一致")
        tab = json.loads(annotations["sh.brew.tab"])
        for dependency in tab.get("runtime_dependencies", []):
            self.locked.setdefault(dependency["full_name"], dependency)
        archive = self.cache / f"{name.replace('@', '-')}-{version}-{digest}.tar.gz"
        url = f"https://ghcr.io/v2/{self.repository(name)}/blobs/sha256:{digest}"
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            print(f"下载 Intel OCR 依赖：{name} {version}", flush=True)
            temporary = archive.with_suffix(".part")
            checksum = hashlib.sha256()
            with self.request(url, self.headers(name)) as response, temporary.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
                    checksum.update(chunk)
            if checksum.hexdigest() != digest:
                temporary.unlink()
                raise RuntimeError(f"下载校验失败：{name}")
            temporary.replace(archive)
        cellar = self.prefix / "Cellar"
        cellar.mkdir(exist_ok=True)
        with tarfile.open(archive) as package:
            package.extractall(cellar, filter="data")
        versions = [directory for directory in (cellar / name).iterdir() if directory.is_dir()]
        if len(versions) != 1:
            raise RuntimeError(f"包版本目录不明确：{name}")
        directory = versions[0]
        self.installed[name] = directory
        opt = self.prefix / "opt"
        opt.mkdir(exist_ok=True)
        (opt / name).symlink_to(Path("../Cellar") / name / directory.name)
        self.manifest.append({"name": name, "version": directory.name, "sha256": digest, "url": url})
        return directory

    def resolve(self, original, library):
        if original.startswith("@loader_path/"):
            target = library.parent / original.removeprefix("@loader_path/")
        elif original.startswith("@rpath/"):
            name = original.removeprefix("@rpath/")
            target = next((path for path in (library.parent / name, library.parent.parent / "lib" / name)
                           if path.exists()), None)
            if target is None:
                raise RuntimeError(f"无法解析 {library.name} 的依赖：{original}")
        else:
            relative = original.replace("@@HOMEBREW_CELLAR@@/", "Cellar/")
            relative = relative.replace("@@HOMEBREW_PREFIX@@/", "")
            if relative.startswith("/usr/local/"):
                relative = relative.removeprefix("/usr/local/")
            if relative.startswith("/"):
                raise RuntimeError(f"未知的 OCR 依赖前缀：{original}")
            parts = Path(relative).parts
            if len(parts) < 2 or parts[0] not in ("Cellar", "opt"):
                raise RuntimeError(f"未知的 OCR 依赖路径：{original}")
            self.install(parts[1])
            target = self.prefix / relative
        if not target.exists():
            raise RuntimeError(f"缺少 OCR 动态库：{target}")
        return target.resolve()

    def relocate(self, executable):
        queue, seen = [executable], set()
        while queue:
            library = queue.pop().resolve()
            if library in seen:
                continue
            seen.add(library)
            if "x86_64" not in subprocess.check_output(["lipo", "-archs", str(library)], text=True).split():
                raise RuntimeError(f"不是 Intel 动态库：{library}")
            links = subprocess.check_output(["otool", "-L", str(library)], text=True).splitlines()[1:]
            changes = [] if library == executable.resolve() else ["-id", "@loader_path/" + library.name]
            for line in links:
                original = line.strip().split(" (")[0]
                if original.startswith(("/usr/lib/", "/System/Library/")):
                    continue
                target = self.resolve(original, library)
                replacement = "@loader_path/" + os.path.relpath(target, library.parent)
                changes += ["-change", original, replacement]
                if target != library:
                    queue.append(target)
            if changes:
                subprocess.run(["install_name_tool", *changes, str(library)], check=True, capture_output=True)
            subprocess.run(["codesign", "--force", "--sign", "-", str(library)], check=True, capture_output=True)
        return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy", help="HTTP 代理地址，例如 http://127.0.0.1:7890")
    parser.add_argument("--tessdata-dir", type=Path, required=True, help="包含 chi_sim、chi_tra、eng 模型的目录")
    args = parser.parse_args()
    languages = ("chi_sim", "chi_tra", "eng")
    for language in languages:
        if not (args.tessdata_dir / f"{language}.traineddata").is_file():
            parser.error(f"缺少模型：{language}")
    vendor = ROOT / "vendor" / "intel"
    vendor.mkdir(parents=True, exist_ok=True)
    cache = vendor / "ocr-downloads"
    cache.mkdir(exist_ok=True)
    prefix = vendor / "ocr-prefix"
    marker = prefix / "intel-ocr-manifest.json"
    if not marker.exists():
        if prefix.exists():
            raise RuntimeError("已有未完成的 OCR 依赖目录，请先检查其内容")
        with tempfile.TemporaryDirectory(prefix=".intel-ocr-", dir=vendor) as staging:
            bottles = Bottles(Path(staging), cache, args.proxy)
            tesseract = bottles.install("tesseract", VERSION)
            binaries = bottles.relocate(tesseract / "bin" / "tesseract")
            manifest = {"tesseract_version": VERSION, "architecture": "x86_64", "bottle_tag": TAG,
                        "binaries": binaries, "bottles": bottles.manifest}
            (Path(staging) / marker.name).write_text(json.dumps(manifest, indent=2) + "\n")
            Path(staging).rename(prefix)
    manifest = json.loads(marker.read_text())
    tesseract = next(package for package in manifest["bottles"] if package["name"] == "tesseract")
    ocr = vendor / "ocr"
    (ocr / "tessdata").mkdir(parents=True, exist_ok=True)
    executable = ocr / "tesseract"
    if executable.is_symlink():
        executable.unlink()
    elif executable.exists():
        raise RuntimeError("已有独立 Tesseract 文件，请检查后再替换")
    executable.symlink_to(Path("../ocr-prefix/Cellar/tesseract") / tesseract["version"] / "bin" / "tesseract")
    for language in languages:
        shutil.copy2(args.tessdata_dir / f"{language}.traineddata", ocr / "tessdata")
    subprocess.run([str(executable), "--version"], check=True)
    print(f"Intel Tesseract {VERSION} 已准备完成：{manifest['binaries']} 个二进制文件，中文简繁体及英文模型")


if __name__ == "__main__":
    main()
