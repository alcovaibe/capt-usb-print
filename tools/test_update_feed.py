"""Офлайн-проверки канала; не создают релизы и не обращаются к GitHub."""
import copy
import importlib.util
from pathlib import Path
import unittest

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

    def test_unpublished_or_unexpected_channel_is_rejected(self):
        for key, value in [("draft", True), ("prerelease", False), ("tag_name", "v1.0.0"), ("html_url", "https://evil.example")]:
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

    def test_malformed_previous_feed_cannot_be_treated_as_empty(self):
        for key, value in [("applicationId", "other.app"), ("schemaVersion", 2), ("channel", "stable"), ("release", {})]:
            old = self.candidate(); old[key] = value
            with self.assertRaises(ValueError): feed.merge_feed(old, self.candidate(151))
        old = self.candidate(); old["release"]["releaseUrl"] = "https://evil.example"
        with self.assertRaises(ValueError): feed.merge_feed(old, self.candidate(151))


if __name__ == "__main__": unittest.main()
