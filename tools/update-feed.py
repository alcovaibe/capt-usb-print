#!/usr/bin/env python3
"""Публикация статического канала только из настоящего подписанного APK после CI."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

REPO = "alcovaibe/capt-usb-print"
APPID = "com.cryonum.captusbprint"
BRANCH = "updates"
PATH = "updates/android.json"
TAG_PATTERN = r"v0\.1\.0-a2-signed\.build\.([1-9][0-9]{0,8})"
ROOT = Path(__file__).resolve().parent.parent


def call(*args):
    return subprocess.run([str(a) for a in args], check=True, capture_output=True, text=True).stdout


def api(endpoint, data=None):
    args = ["gh", "api", f"repos/{REPO}/{endpoint}"]
    if data is not None:
        result = subprocess.run(args + ["--input", "-"], input=json.dumps(data), text=True,
                                capture_output=True, check=True)
        return json.loads(result.stdout)
    return json.loads(call(*args))


def manifest_from_apk(release, badging, certificate, expected):
    match = re.fullmatch(TAG_PATTERN, release["tag_name"])
    if not match or release["draft"]:
        raise ValueError("Expected published signed release")
    build = int(match[1])
    name = f"0.1.0-a2.{build}"
    metadata = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if (not metadata or metadata[1] != APPID or int(metadata[2]) != 1000 + build
            or metadata[3] != name or "application-debuggable" in badging
            or "sdkVersion:'29'" not in badging or certificate != expected):
        raise ValueError("APK identity, signature or version differs")
    url = f"https://github.com/{REPO}/releases/tag/{release['tag_name']}"
    if release["html_url"] != url:
        raise ValueError("Wrong release URL")
    return {"schemaVersion": 1, "platform": "android", "channel": "experimental", "applicationId": APPID,
            "release": {"versionCode": int(metadata[2]), "versionName": name, "minSdk": 29,
                        "releaseUrl": url,
                        "notes": {"ru": "Новая подписанная сборка CAPT USB Print.",
                                  "en": "New signed CAPT USB Print build."}}}


def merge_feed(previous, candidate):
    # Validate identity and metadata even when preserving an already newer release.
    if set(previous) != set(candidate) or any(previous.get(k) != candidate[k] for k in candidate if k != "release"):
        raise ValueError("Invalid previous channel")
    old = previous["release"]
    if old is not None:
        if (not isinstance(old, dict) or set(old) != set(candidate["release"])
                or type(old.get("versionCode")) is not int or not 1 <= old["versionCode"] <= 2100000000
                or old.get("minSdk") != 29 or not isinstance(old.get("notes"), dict)
                or set(old["notes"]) != {"ru", "en"}
                or any(not isinstance(s, str) or not s.strip() or len(s) > 1500 for s in old["notes"].values())):
            raise ValueError("Invalid previous release")
        build = old["versionCode"] - 1000
        if (old["versionName"] != f"0.1.0-a2.{build}" or build < 1
                or old["releaseUrl"] != f"https://github.com/{REPO}/releases/tag/v0.1.0-a2-signed.build.{build}"):
            raise ValueError("Invalid previous release identity")
        if old["versionCode"] >= candidate["release"]["versionCode"]:
            if old["versionCode"] == candidate["release"]["versionCode"] and old != candidate["release"]:
                raise ValueError("Conflicting release for same versionCode")
            return previous
    return candidate


def release_snapshot(release):
    # Download counters change when we inspect the asset; compare publication identity only.
    asset_fields = ("id", "name", "state", "size", "content_type", "created_at", "updated_at",
                    "digest", "browser_download_url")
    return {**{k: release.get(k) for k in ("draft", "prerelease", "tag_name", "html_url")},
            "assets": [{k: a.get(k) for k in asset_fields} for a in release["assets"]]}


def read_optional(endpoint):
    # Only a real 404 initializes a missing ref/file. Other failures stop publication.
    result = subprocess.run(["gh", "api", f"repos/{REPO}/{endpoint}"], text=True, capture_output=True)
    if result.returncode == 0:
        return json.loads(result.stdout)
    if "(HTTP 404)" in result.stderr:
        return None
    raise RuntimeError(result.stderr)


def publish():
    if os.environ.get("GITHUB_REPOSITORY") != REPO or os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise RuntimeError("Publication requires trusted repository main")
    releases = json.loads(call("gh", "api", "--paginate", "--slurp", f"repos/{REPO}/releases?per_page=100"))
    candidates = [r for page in releases for r in page if re.fullmatch(TAG_PATTERN, r["tag_name"]) and not r["draft"]]
    if not candidates:
        print("No signed releases to advertise")
        return
    release = max(candidates, key=lambda r: int(re.fullmatch(TAG_PATTERN, r["tag_name"])[1]))
    tag = release["tag_name"]
    build = re.fullmatch(TAG_PATTERN, tag)[1]
    commit = api(f"commits/{tag}")["sha"]
    runs = api(f"actions/workflows/android.yml/runs?head_sha={commit}&status=success&per_page=100")["workflow_runs"]
    if not any(r["conclusion"] == "success" and r["head_branch"] == "main" and r["event"] in {"push", "workflow_dispatch"} for r in runs):
        raise RuntimeError("No successful trusted signed-release workflow for tag commit")
    expected_name = f"CAPT-USB-Print-0.1.0-a2-build-{build}.apk"
    assets = [a for a in release["assets"] if a["name"].endswith(".apk")]
    if len(assets) != 1 or assets[0]["name"] != expected_name or assets[0]["state"] != "uploaded":
        raise RuntimeError("Release needs exactly one expected uploaded APK")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    expected = (ROOT / "config/release-certificate.sha256").read_text().strip().lower()
    if not re.fullmatch("[0-9a-f]{64}", expected):
        raise RuntimeError("Invalid pinned certificate")
    with tempfile.TemporaryDirectory() as tmp:
        call("gh", "release", "download", tag, "--repo", REPO, "--pattern", expected_name, "--dir", tmp)
        apk = Path(tmp) / expected_name
        if not 10000 <= apk.stat().st_size <= 100 * 1024 * 1024 or apk.stat().st_size != assets[0]["size"]:
            raise RuntimeError("Invalid APK size")
        output = call(tools / "apksigner", "verify", "--verbose", "--print-certs", apk)
        signers = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)", output)
        if len(signers) != 1:
            raise RuntimeError("Expected one APK signer")
        candidate = manifest_from_apk(release, call(tools / "aapt", "dump", "badging", apk), signers[0].lower(), expected)
        digest = "sha256:" + hashlib.sha256(apk.read_bytes()).hexdigest()
        if assets[0].get("digest") not in (None, digest):
            raise RuntimeError("Asset digest mismatch")
    # Recheck the release/asset after inspecting the actual download.
    fresh = api(f"releases/{release['id']}")
    if release_snapshot(fresh) != release_snapshot(release):
        raise RuntimeError("Release changed during verification")
    if api(f"commits/{tag}")["sha"] != commit:
        raise RuntimeError("Tag changed during verification")
    ref = read_optional(f"git/ref/heads/{BRANCH}")
    if ref is None:
        # Separate metadata ref; never write main or create another signed release.
        api("git/refs", {"ref": f"refs/heads/{BRANCH}", "sha": os.environ["GITHUB_SHA"]})
    entry = read_optional(f"contents/{PATH}?ref={BRANCH}")
    if entry is not None:
        raw = base64.b64decode(entry["content"], validate=False)
        if len(raw) > 16384:
            raise RuntimeError("Oversized previous manifest")
        previous = json.loads(raw)
        if merge_feed(previous, candidate) == previous:
            print("Feed already advertises this or a newer verified build")
            return
    body = {"message": f"updates: announce verified signed build {build}", "branch": BRANCH,
            "content": base64.b64encode((json.dumps(candidate, ensure_ascii=False, indent=2) + "\n").encode()).decode()}
    if entry is not None:
        body["sha"] = entry["sha"]  # Contents API rejects concurrent replacement; never force.
    api(f"contents/{PATH}", body)
    actual = api(f"contents/{PATH}?ref={BRANCH}")
    if json.loads(base64.b64decode(actual["content"])) != candidate:
        raise RuntimeError("Published feed differs from verified candidate")
    print("Published verified update metadata:", candidate["release"]["versionName"])


if __name__ == "__main__":
    publish()
