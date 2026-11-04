"""Проверка и публикация R8 APK всех ABI с постоянной подписью и совместимым universal."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from release_apks import ABIS, VARIANTS, MAX_APK_BYTES, asset_name, built_apks, verify_native_layout

ROOT = Path.cwd()
OUTPUT = ROOT / "release-output"
APPID = "com.cryonum.captusbprint"


def call(*args):
    return subprocess.run([str(x) for x in args], check=True, text=True, capture_output=True).stdout


def fingerprint(path):
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    output = call(tools / "apksigner", "verify", "--verbose", "--print-certs", path)
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)", output)
    if len(signers) != 1:
        raise RuntimeError("Expected exactly one APK signer")
    return signers[0].lower()


def badging(path):
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    output = call(tools / "aapt", "dump", "badging", path)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if (not match or match[1] != APPID or "application-debuggable" in output or "sdkVersion:'29'" not in output):
        raise RuntimeError("Invalid release APK identity")
    return int(match[2]), match[3]


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def expected_certificate():
    value = (ROOT / "config/release-certificate.sha256").read_text().strip().lower()
    if not re.fullmatch("[0-9a-f]{64}", value):
        raise RuntimeError("Invalid pinned certificate")
    return value


def verify():
    OUTPUT.mkdir(exist_ok=True)
    build = int(os.environ["GITHUB_RUN_NUMBER"])
    code = int(os.environ["APP_VERSION_CODE"])
    expected = expected_certificate()
    apks = built_apks(ROOT / "app/build/outputs/apk/release", signed=True)
    checksums = []
    for variant, apk in apks.items():
        if not 10000 <= apk.stat().st_size <= MAX_APK_BYTES or fingerprint(apk) != expected:
            raise RuntimeError(f"Invalid size or signer: {variant}")
        if badging(apk) != (code, f"0.1.0-a2.{build}"):
            raise RuntimeError(f"Unexpected release version: {variant}")
        verify_native_layout(apk, variant)
        target = OUTPUT / asset_name(build, variant)
        shutil.copyfile(apk, target)
        checksums.append(f"{sha(target)}  {target.name}\n")
    mapping = ROOT / "app/build/outputs/mapping/release/mapping.txt"
    if not mapping.is_file() or mapping.stat().st_size == 0:
        raise RuntimeError("R8 mapping is missing")
    shutil.copyfile(mapping, OUTPUT / "mapping.txt")
    old = json.loads(call("gh", "release", "list", "--limit", "100", "--json", "tagName"))
    prior = [(int(m[1]), row["tagName"]) for row in old
             if (m := re.fullmatch(r"v0\.1\.0-a2-signed\.build\.([1-9][0-9]*)", row["tagName"]))]
    if prior:
        previous_build, previous_tag = max(prior)
        # Select one compatible prior asset, never the first file in a directory.
        metadata = json.loads(call("gh", "release", "view", previous_tag, "--json", "assets"))
        names = {a["name"] for a in metadata["assets"]}
        name = asset_name(previous_build, "x86_64")
        if name not in names:
            name = asset_name(previous_build)
        if name not in names:
            raise RuntimeError("Previous release has no compatible emulator APK")
        directory = ROOT / "upgrade-input"
        directory.mkdir(exist_ok=True)
        call("gh", "release", "download", previous_tag, "--pattern", name, "--dir", directory)
        old_apk = directory / name
        if fingerprint(old_apk) != expected or badging(old_apk)[0] >= code:
            raise RuntimeError("Prior signer/version prevents an in-place upgrade")
        shutil.copyfile(old_apk, directory / "previous.apk")
    sources = ROOT / "release-office-sources/office-corresponding-sources.tar.gz"
    checksum = sources.with_suffix(sources.suffix + ".sha256")
    if not sources.is_file() or not checksum.is_file() or sha(sources) != checksum.read_text().split()[0]:
        raise RuntimeError("Verified corresponding Office source bundle required")
    shutil.copyfile(sources, OUTPUT / sources.name)
    shutil.copyfile(checksum, OUTPUT / checksum.name)
    (OUTPUT / "release-name.txt").write_text(asset_name(build) + "\n")
    (OUTPUT / "emulator-release-name.txt").write_text(asset_name(build, "x86_64") + "\n")
    (OUTPUT / "SHA256SUMS.txt").write_text("".join(checksums))
    print("Verified signed R8 APKs:", ", ".join(VARIANTS))


def publish():
    build = int(os.environ["GITHUB_RUN_NUMBER"])
    tag = f"v0.1.0-a2-signed.build.{build}"
    if not (OUTPUT / "upgrade-check.txt").read_text().startswith("PASS:"):
        raise RuntimeError("Emulator upgrade check missing")
    expected = expected_certificate()
    sums = dict(line.split("  ", 1)[::-1] for line in (OUTPUT / "SHA256SUMS.txt").read_text().splitlines())
    apks = [OUTPUT / asset_name(build, variant) for variant in VARIANTS]
    if set(sums) != {apk.name for apk in apks}:
        raise RuntimeError("Incomplete APK checksums")
    for variant, apk in zip(VARIANTS, apks):
        if sha(apk) != sums[apk.name] or fingerprint(apk) != expected:
            raise RuntimeError("APK changed since verification")
        verify_native_layout(apk, variant)
    if subprocess.run(["gh", "release", "view", tag], capture_output=True).returncode == 0:
        raise RuntimeError("Signed tag already exists; refusing to overwrite assets")
    sources = OUTPUT / "office-corresponding-sources.tar.gz"
    checksum = sources.with_suffix(sources.suffix + ".sha256")
    if sha(sources) != checksum.read_text().split()[0]:
        raise RuntimeError("Source bundle changed")
    notes = """Подписанные APK с R8: arm64-v8a для современных телефонов, armeabi-v7a для 32-битного Android, x86_64 и универсальный APK. Встроенное обновление автоматически выбирает архитектуру устройства; старые загрузчики продолжают получать универсальный файл.

Кнопка главного экрана переименована в «Выбрать файл». Поддерживаются PDF, фото, DOCX, DOC, XLS и XLSX. Документы Office преобразуются локально через LibreOffice; абсолютное совпадение с Microsoft Office не гарантируется, физическая печать Office ещё требует аппаратной приёмки.

Android 10+, постоянная подпись и обновление поверх приложения без удаления данных. Для ручной установки выбирайте APK своей архитектуры или universal (файл без суффикса архитектуры). Соответствующие исходники движка приложены рядом с APK.
"""
    note_path = OUTPUT / "notes.md"
    note_path.write_text(notes)
    call("gh", "release", "create", tag, *apks, sources, checksum, OUTPUT / "SHA256SUMS.txt",
         "--target", os.environ["GITHUB_SHA"], "--title", f"CAPT USB Print · build {build}",
         "--notes-file", note_path, "--latest=true")
    print("PUBLISHED:", call("gh", "release", "view", tag, "--json", "url", "--jq", ".url").strip())


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("verify", "publish"):
        raise SystemExit("Usage: python3 tools/signed-release.py verify|publish")
    (verify if sys.argv[1] == "verify" else publish)()
