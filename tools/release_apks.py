"""Имена и состав автономных APK: три ABI и универсальный файл для старых загрузчиков."""
from pathlib import Path
import zipfile

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
VARIANTS = ("universal", *ABIS)
MAX_APK_BYTES = 1024 * 1024 * 1024


def asset_name(build, variant="universal"):
    if variant not in VARIANTS or not str(build).isdigit() or int(build) < 1:
        raise ValueError("Invalid APK variant or build")
    suffix = "" if variant == "universal" else "-" + variant
    return f"CAPT-USB-Print-0.1.0-a2-build-{build}{suffix}.apk"


def built_apks(directory, signed):
    suffix = "" if signed else "-unsigned"
    result = {variant: Path(directory) / f"app-{variant}-release{suffix}.apk" for variant in VARIANTS}
    if set(Path(directory).glob("*.apk")) != set(result.values()):
        raise ValueError("Expected universal and all three ABI release APKs")
    return result


def release_assets(release):
    """До перехода разрешён один универсальный APK; после перехода обязательны все четыре."""
    build = release["tag_name"].rsplit(".", 1)[-1]
    assets = [a for a in release["assets"] if a["name"].endswith(".apk")]
    by_name = {a["name"]: a for a in assets}
    wanted = {asset_name(build, variant): variant for variant in VARIANTS}
    if len(assets) != len(by_name) or set(by_name) not in ({asset_name(build)}, set(wanted)):
        raise ValueError("Incomplete or unexpected release APK set")
    if any(a["state"] != "uploaded" for a in assets):
        raise ValueError("APK upload is incomplete")
    return {wanted[name]: asset for name, asset in by_name.items()}


def verify_native_layout(apk, variant):
    expected = set(ABIS) if variant == "universal" else {variant}
    if variant not in VARIANTS:
        raise ValueError("Unknown APK variant")
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        actual = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        if actual != expected:
            raise ValueError(f"Wrong native ABIs in {apk}: {actual}, expected {expected}")
        for abi in expected:
            for library in ("liblo-native-code.so", "libcapt_hiscoa.so", "libcapt_office_limits.so", "libcapt_office_sandbox.so"):
                if f"lib/{abi}/{library}" not in names:
                    raise ValueError(f"Missing {library} for {abi}")
