"""Проверки перехода между universal и ABI APK без обращения к сети или ключам подписи."""
from pathlib import Path
import tempfile
import unittest
import zipfile
from release_apks import ABIS, VARIANTS, asset_name, built_apks, release_assets, verify_native_layout


class ReleaseApksTest(unittest.TestCase):
    def release(self, variants):
        return {"tag_name": "v0.1.0-a2-signed.build.250", "assets": [
            {"name": asset_name(250, v), "state": "uploaded"} for v in variants]}

    def test_legacy_universal_and_complete_new_releases_are_accepted(self):
        self.assertEqual({"universal"}, set(release_assets(self.release(["universal"]))))
        self.assertEqual(set(VARIANTS), set(release_assets(self.release(VARIANTS))))

    def test_missing_duplicate_unexpected_or_uploading_assets_are_rejected(self):
        for variants in ([], ABIS, ["arm64-v8a"], ["universal", "arm64-v8a"], ["universal", "universal"]):
            with self.assertRaises(ValueError): release_assets(self.release(variants))
        release = self.release(VARIANTS); release["assets"][0]["state"] = "new"
        with self.assertRaises(ValueError): release_assets(release)
        release = self.release(VARIANTS); release["assets"][0]["name"] = "unexpected.apk"
        with self.assertRaises(ValueError): release_assets(release)

    def test_filename_mapping_preserves_old_updater_url(self):
        self.assertEqual("CAPT-USB-Print-0.1.0-a2-build-250.apk", asset_name(250))
        for abi in ABIS: self.assertTrue(asset_name(250, abi).endswith(f"-{abi}.apk"))
        for variant in ("arm", "../arm64-v8a", ""):
            with self.assertRaises(ValueError): asset_name(250, variant)

    def test_each_apk_contains_exactly_its_abis_and_required_bridges(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "app.apk"
            for variant in VARIANTS:
                abis = ABIS if variant == "universal" else [variant]
                with zipfile.ZipFile(path, "w") as archive:
                    for abi in abis:
                        for lib in ("liblo-native-code.so", "libcapt_hiscoa.so", "libcapt_office_limits.so", "libcapt_office_sandbox.so"):
                            archive.writestr(f"lib/{abi}/{lib}", b"fixture")
                verify_native_layout(path, variant)
                wrong = "x86_64" if variant != "x86_64" else "arm64-v8a"
                with self.assertRaises(ValueError): verify_native_layout(path, wrong)
                with zipfile.ZipFile(path, "a") as archive:
                    archive.writestr("lib/unknown/libunexpected.so", b"fixture")
                with self.assertRaises(ValueError): verify_native_layout(path, variant)
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("lib/arm64-v8a/liblo-native-code.so", b"fixture")
            with self.assertRaises(ValueError): verify_native_layout(path, "arm64-v8a")

    def test_build_outputs_must_include_every_variant_and_no_stale_apks(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            for signed in (True, False):
                suffix = "" if signed else "-unsigned"
                for variant in VARIANTS: (directory / f"app-{variant}-release{suffix}.apk").touch()
                self.assertEqual(set(VARIANTS), set(built_apks(directory, signed)))
                (directory / "stale.apk").touch()
                with self.assertRaises(ValueError): built_apks(directory, signed)
                for file in directory.iterdir(): file.unlink()


if __name__ == "__main__": unittest.main()
