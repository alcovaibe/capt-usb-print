"""Verify signed APK, pinned certificate, version and prior release; publish only after emulator validation."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path.cwd()
OUTPUT = ROOT / "release-output"
APPID = "com.cryonum.captusbprint"
EXPECTED = (ROOT / "config/release-certificate.sha256").read_text().strip().lower()
TOOLS = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
RUN_NUMBER = int(os.environ["GITHUB_RUN_NUMBER"])
VERSION_CODE = int(os.environ["APP_VERSION_CODE"])
SOURCE = os.environ["GITHUB_SHA"]
APK = ROOT / "app/build/outputs/apk/release/app-release.apk"
TAG = "v0.1.0-a2-signed.build." + str(RUN_NUMBER)
OUTPUT.mkdir(exist_ok=True)

def call(*args):
    return subprocess.run([str(x) for x in args], check=True, text=True, capture_output=True).stdout

def fingerprint(path):
    output = call(TOOLS / "apksigner", "verify", "--verbose", "--print-certs", path)
    match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)", output)
    if not match:
        raise RuntimeError("APK signature verification failed: no signer")
    return match.group(1).lower()

def badging(path):
    output = call(TOOLS / "aapt", "dump", "badging", path)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output)
    if not match:
        raise RuntimeError("Invalid APK package")
    assert match.group(1) == APPID, "Release applicationId changed"
    assert "application-debuggable" not in output, "Release must not be debuggable"
    assert "sdkVersion:'29'" in output, "Unexpected minSdk"
    return int(match.group(2)), match.group(3)

def verify():
    assert len(EXPECTED) == 64 and all(c in '0123456789abcdef' for c in EXPECTED)
    assert APK.is_file() and APK.stat().st_size > 10000
    assert fingerprint(APK) == EXPECTED, "Release signer does not match pinned certificate"
    code, name = badging(APK)
    assert code == VERSION_CODE, "Expected release versionCode differs"
    assert name == f"0.1.0-a2.{RUN_NUMBER}", "Expected release versionName differs"
    old = json.loads(call("gh", "release", "list", "--limit", "100", "--json", "tagName"))
    prior = []
    for row in old:
        match = re.fullmatch(r"v[0-9].*-signed\.build\.(\d+)", row["tagName"])
        if match:
            prior.append((int(match.group(1)), row["tagName"]))
    previous_tag = max(prior)[1] if prior else None
    if previous_tag:
        directory = ROOT / "upgrade-input"
        directory.mkdir(exist_ok=True)
        call("gh", "release", "download", previous_tag, "--pattern", "*.apk", "--dir", directory)
        candidates = list(directory.glob("*.apk"))
        assert len(candidates) == 1, "Previous release must have exactly one APK"
        old_apk = candidates[0]
        assert fingerprint(old_apk) == EXPECTED, "Previous release has a different signing certificate"
        old_code, _ = badging(old_apk)
        assert old_code < code, "New versionCode must be greater"
        (ROOT / "upgrade-input/previous.apk").write_bytes(old_apk.read_bytes())
    else:
        print("First permanent-key release; no previous signed APK exists.")
    release_apk = OUTPUT / f"CAPT-USB-Print-0.1.0-a2-build-{RUN_NUMBER}.apk"
    release_apk.write_bytes(APK.read_bytes())
    (OUTPUT / "release-name.txt").write_text(release_apk.name)
    (OUTPUT / "SHA256SUMS.txt").write_text(hashlib.sha256(release_apk.read_bytes()).hexdigest() + "  " + release_apk.name + "\n")
    print("Release signature, package, version and previous version validated:", release_apk.name)

def publish():
    release_name = (OUTPUT / "release-name.txt").read_text().strip()
    release_apk = OUTPUT / release_name
    assert release_apk.is_file()
    assert (OUTPUT / "upgrade-check.txt").read_text().startswith("PASS:"), "Emulator check missing"
    assert fingerprint(release_apk) == EXPECTED, "Release modified since verification"
    existing = subprocess.run(["gh", "release", "view", TAG], capture_output=True)
    if existing.returncode == 0:
        raise RuntimeError("Signed release tag already exists; refusing to overwrite assets")
    digest = hashlib.sha256(release_apk.read_bytes()).hexdigest()
    notes = f"""# CAPT USB Print — подписанная диагностическая сборка A2

**Версия:** 0.1.0-a2.{RUN_NUMBER} (versionCode {VERSION_CODE}).

- Постоянная отдельная подпись для обновлений поверх установленных версий.
- Технический отчёт свёрнут по умолчанию; интерфейс адаптирован для узкого экрана.
- Приложение **пока не печатает**. Эта версия предназначена для USB-диагностики.
- Сборка, lint, unit-тесты, разрешения APK, сертификат и установка/обновление на эмуляторе Android 16 проверены.
- Отпечаток на Canon LBP2900 и работа на HONOR X9c ещё не подтверждены.
- Исходный коммит: {SOURCE}
- SHA-256 APK: {digest}
- SHA-256 сертификата: {EXPECTED}

**Установка и обновления:** установите этот подписанный APK один раз. Новые подписанные сборки ставятся поверх без удаления приложения и данных. Существующий debug APK с суффиксом пакета .debug является отдельным приложением и в эту цепочку обновлений не входит. Доступен только участникам приватного репозитория.
"""
    note_path = OUTPUT / "notes.md"
    note_path.write_text(notes)
    call("gh", "release", "create", TAG, release_apk, "--target", SOURCE, "--title",
         f"CAPT USB Print · Signed A2 · build {RUN_NUMBER}", "--notes-file", note_path,
         "--prerelease", "--latest=false")
    print("PUBLISHED:", call("gh", "release", "view", TAG, "--json", "url", "--jq", ".url").strip())

if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in ("verify", "publish"):
        raise SystemExit("Usage: python3 tools/signed-release.py verify|publish")
    (verify if sys.argv[1] == "verify" else publish)()
