#!/usr/bin/env python3
"""Inventory build outputs without invoking an Office parser or reading user data."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('package', type=Path)
    parser.add_argument('abi', choices=('arm64-v8a', 'armeabi-v7a', 'x86_64'))
    args = parser.parse_args()
    libraries = args.package / 'jniLibs' / args.abi
    lib = libraries / 'liblo-native-code.so'
    assert lib.is_file()
    elf = subprocess.run(['readelf', '-h', str(lib)], check=True, text=True, capture_output=True).stdout
    machine = {'arm64-v8a': 'AArch64', 'armeabi-v7a': 'ARM', 'x86_64': 'X86-64'}[args.abi]
    assert machine in elf, 'Unexpected native architecture'
    symbols = subprocess.run(['readelf', '--dyn-syms', '--wide', str(lib)],
        check=True, text=True, capture_output=True).stdout
    assert 'libreofficekit_hook' in symbols, 'LOKit entry point missing'
    files = {}
    for file in sorted(args.package.rglob('*')):
        if file.is_file():
            files[str(file.relative_to(args.package))] = {
                'bytes': file.stat().st_size, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()}
    metadata = {'abi': args.abi, 'uncompressedBytes': sum(f['bytes'] for f in files.values()),
        'runtimeRenderingVerified': False, 'files': files}
    (args.package / 'inventory.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(f'OFFICE_NATIVE_INVENTORY abi={args.abi} bytes={metadata["uncompressedBytes"]}')


if __name__ == '__main__':
    main()
