"""Офлайн-проверки канала; не создают релизы и не обращаются к GitHub."""
import base64
import contextlib
import io
import json
import os
import zipfile
import hashlib
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from release_apks import ABIS, VARIANTS, asset_name

spec = importlib.util.spec_from_file_location("feed", Path(__file__).with_name("update-feed.py"))
feed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(feed)


class FeedTests(unittest.TestCase):
    def release(self, build=150):
        tag = f"v0.1.0-a2-signed.build.{build}"
        return {"tag_name": tag, "draft": False, "prerelease": True,
                "html_url": f"https://github.com/{feed.REPO}/releases/tag/{tag}"}

    def badging(self, build=150):
        return (f"package: name='{feed.APPID}' versionCode='{1000+build}' versionName='0.1.0-a2.{build}'\n"
                "sdkVersion:'29'\n")

    def candidate(self, build=150):
        return feed.manifest_from_apk(self.release(build), self.badging(build), "a"*64, "a"*64)

    def test_content_write_uses_put_and_ref_creation_uses_post(self):
        with patch.object(feed.subprocess, "run", return_value=SimpleNamespace(stdout="{}")) as run:
            feed.api("contents/updates/android.json", {"content": "e30="}, method="PUT")
            self.assertEqual(["--method", "PUT", "--input", "-"], run.call_args.args[0][-4:])
            feed.api("git/refs", {"ref": "refs/heads/updates", "sha": "a"*40})
            self.assertEqual(["--method", "POST", "--input", "-"], run.call_args.args[0][-4:])

    def test_download_counter_does_not_change_release_identity(self):
        release = dict(self.release(), assets=[{"id": 1, "name": "app.apk", "state": "uploaded", "size": 10000,
                                              "digest": "sha256:abc", "download_count": 0}])
        downloaded = copy.deepcopy(release); downloaded["assets"][0]["download_count"] = 1
        self.assertEqual(feed.release_snapshot(release), feed.release_snapshot(downloaded))
        for key, value in [("id", 2), ("name", "replaced.apk"), ("size", 11000), ("digest", "sha256:def"), ("state", "new")]:
            replaced = copy.deepcopy(downloaded); replaced["assets"][0][key] = value
            self.assertNotEqual(feed.release_snapshot(release), feed.release_snapshot(replaced))

    def test_metadata_comes_from_verified_actual_apk(self):
        result = self.candidate()["release"]
        self.assertEqual(1150, result["versionCode"])
        self.assertEqual("0.1.0-a2.150", result["versionName"])
        self.assertEqual(29, result["minSdk"])

    def test_wrong_signature_package_debug_sdk_or_version_never_publishes(self):
        bad = self.badging()
        for text in [bad.replace(feed.APPID, feed.APPID+".debug"), bad+"application-debuggable\n",
                     bad.replace("1150", "1149"), bad.replace("a2.150", "a2.151"), bad.replace("'29'", "'30'")]:
            with self.assertRaises(ValueError):
                feed.manifest_from_apk(self.release(), text, "a"*64, "a"*64)
        with self.assertRaises(ValueError):
            feed.manifest_from_apk(self.release(), bad, "b"*64, "a"*64)

    def test_regular_release_is_accepted(self):
        release = self.release(); release["prerelease"] = False
        result = feed.manifest_from_apk(release, self.badging(), "a"*64, "a"*64)
        self.assertEqual(1150, result["release"]["versionCode"])

    def test_unpublished_or_unexpected_channel_is_rejected(self):
        for key, value in [("draft", True), ("tag_name", "v1.0.0"), ("html_url", "https://evil.example")]:
            release = self.release(); release[key] = value
            with self.assertRaises(ValueError):
                feed.manifest_from_apk(release, self.badging(), "a"*64, "a"*64)

    def test_feed_never_downgrades_and_rejects_same_code_conflict(self):
        old, new = self.candidate(150), self.candidate(151)
        self.assertEqual(new, feed.merge_feed(old, new))
        self.assertEqual(new, feed.merge_feed(new, old))
        self.assertEqual(new, feed.merge_feed(new, new))
        empty = dict(old, release=None)
        self.assertEqual(old, feed.merge_feed(empty, old))
        conflict = copy.deepcopy(old); conflict["release"]["notes"]["ru"] = "other"
        with self.assertRaises(ValueError): feed.merge_feed(old, conflict)

    def test_publication_verifies_every_variant_and_rejects_one_bad_apk(self):
        expected = (feed.ROOT / "config/release-certificate.sha256").read_text().strip()
        environment = {"GITHUB_REPOSITORY": feed.REPO, "GITHUB_REF": "refs/heads/main", "ANDROID_HOME": "/sdk"}
        for mode in ("legacy", "split", "digest", "signature", "layout", "version"):
            with self.subTest(mode=mode):
                variants = ["universal"] if mode == "legacy" else VARIANTS
                payloads = {}
                for variant in variants:
                    stream = io.BytesIO()
                    with zipfile.ZipFile(stream, "w") as archive:
                        for abi in ABIS if variant == "universal" else [variant]:
                            for library in ("liblo-native-code.so", "libcapt_hiscoa.so", "libcapt_office_limits.so", "libcapt_office_sandbox.so"):
                                archive.writestr(f"lib/{abi}/{library}", b"fixture")
                        archive.writestr("assets/padding", b"x" * 10000)
                        if mode == "layout" and variant == "armeabi-v7a":
                            archive.writestr("lib/x86_64/unexpected.so", b"bad")
                    payloads[asset_name(150, variant)] = stream.getvalue()
                release = dict(self.release(), id=1, assets=[
                    {"name": name, "state": "uploaded", "size": len(data),
                     "digest": "sha256:" + hashlib.sha256(data).hexdigest()} for name, data in payloads.items()])
                if mode == "digest": release["assets"][2]["digest"] = "sha256:" + "0" * 64
                verified = []
                def call(*args):
                    if args[:2] == ("gh", "api"): return json.dumps([[release]])
                    if args[:3] == ("gh", "release", "download"):
                        for name, data in payloads.items(): (Path(args[-1]) / name).write_bytes(data)
                        return ""
                    name = Path(args[-1]).name
                    if Path(args[0]).name == "apksigner":
                        verified.append(name)
                        signer = "0" * 64 if mode == "signature" and name.endswith("-armeabi-v7a.apk") else expected
                        return "Signer #1 certificate SHA-256 digest: " + signer
                    if Path(args[0]).name == "aapt":
                        return self.badging(151 if mode == "version" and name.endswith("-x86_64.apk") else 150)
                    raise AssertionError(args)
                def api(endpoint, *args, **kwargs):
                    if endpoint.startswith("commits/"): return {"sha": "a" * 40}
                    if endpoint.startswith("actions/"):
                        return {"workflow_runs": [{"conclusion": "success", "head_branch": "main", "event": "push"}]}
                    if endpoint == "releases/1": return release
                    raise AssertionError("Unexpected publication: " + endpoint)
                previous = {"content": base64.b64encode(json.dumps(self.candidate()).encode()).decode()}
                with patch.dict(os.environ, environment), patch.object(feed, "call", side_effect=call), \
                        patch.object(feed, "api", side_effect=api), patch.object(feed, "read_optional", return_value=previous), \
                        contextlib.redirect_stdout(io.StringIO()):
                    if mode in ("legacy", "split"):
                        feed.publish()
                        self.assertEqual(set(payloads), set(verified))
                    else:
                        with self.assertRaises((ValueError, RuntimeError)): feed.publish()

    def test_malformed_previous_feed_cannot_be_treated_as_empty(self):
        for key, value in [("applicationId", "other.app"), ("schemaVersion", 2), ("channel", "stable"), ("release", {})]:
            old = self.candidate(); old[key] = value
            with self.assertRaises(ValueError): feed.merge_feed(old, self.candidate(151))
        old = self.candidate(); old["release"]["releaseUrl"] = "https://evil.example"
        with self.assertRaises(ValueError): feed.merge_feed(old, self.candidate(151))


if __name__ == "__main__": unittest.main()
