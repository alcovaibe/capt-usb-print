#!/usr/bin/env python3
"""Combine verified pinned ABI packages; reject divergent shared resources."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def assemble(packages, output, abis):
    assert not output.exists(), 'Output must be new'
    output.mkdir(parents=True)
    shared = {}
    for abi in abis:
        package = packages / abi
        subprocess.run([sys.executable, str(Path(__file__).with_name('prepare-office-notices.py')), str(package)], check=True)
        for folder in ('assets', 'assets_strippedUI'):
            for source in sorted((package / folder).rglob('*')):
                if not source.is_file():
                    continue
                relative = source.relative_to(package)
                if relative.parts[:2] == ('assets', 'office-licenses'):
                    relative = Path('assets/office-licenses') / abi / source.relative_to(package / 'assets/office-licenses')
                else:
                    value = digest(source)
                    assert str(relative) not in shared or shared[str(relative)] == value, f'Divergent resource: {relative}'
                    shared[str(relative)] = value
                target = output / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.exists():
                    shutil.copyfile(source, target)
        shutil.copytree(package / 'jniLibs' / abi, output / 'jniLibs' / abi)
    # Keep the default reader's top-level notices, plus every ABI's exact provenance.
    preferred = 'x86_64' if 'x86_64' in abis else abis[0]
    shutil.copytree(packages / preferred / 'assets/office-licenses', output / 'assets/office-licenses', dirs_exist_ok=True)
    notices = output / 'assets/office-licenses'
    index = [{'file': str(p.relative_to(notices)), 'bytes': p.stat().st_size, 'sha256': digest(p)}
             for p in sorted(notices.rglob('*')) if p.is_file() and p != notices / 'index.json']
    (notices / 'index.json').write_text(json.dumps(index, ensure_ascii=False, indent=2) + '\n')
    (output / 'assembled.json').write_text(json.dumps({'abis': abis, 'shared': shared}, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('packages', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--abis', nargs='+', default=['arm64-v8a', 'armeabi-v7a', 'x86_64'])
    args = parser.parse_args()
    assert len(set(args.abis)) == len(args.abis) and set(args.abis) <= {'arm64-v8a', 'armeabi-v7a', 'x86_64'}
    assemble(args.packages, args.output, args.abis)
