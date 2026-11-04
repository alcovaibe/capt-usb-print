#!/usr/bin/env python3
"""Проверить архитектуры всех release APK и сохранить фактические измерения размеров."""
from pathlib import Path
import subprocess
import sys
from release_apks import built_apks, verify_native_layout

root = Path(__file__).resolve().parents[1]
for variant, apk in built_apks(root / "app/build/outputs/apk/release", signed=False).items():
    verify_native_layout(apk, variant)
    subprocess.run([sys.executable, str(root / "tools/measure-office-probe-apk.py"), str(apk),
                    f"office-release-{variant}-apk-size.json"], check=True)
mapping = root / "app/build/outputs/mapping/release/mapping.txt"
if not mapping.is_file() or mapping.stat().st_size == 0:
    raise RuntimeError("Release R8 mapping is missing")
print("CAPT_R8_ABI_RELEASE_LAYOUT_PASSED")
