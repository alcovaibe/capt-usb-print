#!/usr/bin/env python3
"""Preserve exact build inputs and dependency notices; never inspect user files."""
import hashlib
import json
from pathlib import Path
import shutil
import sys

source, package = map(Path, sys.argv[1:])
evidence = package / 'licenses' / 'build-inputs'
evidence.mkdir(parents=True, exist_ok=True)
for name in ('config_host.mk', 'download.lst', 'autogen.input', 'distro-configs/LibreOfficeAndroid.conf'):
    path = source / name
    if path.is_file():
        target = evidence / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
font_source = source / 'extras/source/truetype/symbol'
for name in ('README', 'OpenSymbol.sfd'):
    path = font_source / name
    if path.is_file():
        target = evidence / 'OpenSymbol' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)

archives = []
for path in sorted((source / 'external/tarballs').glob('*')):
    if path.is_file():
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        archives.append({'file': path.name, 'bytes': path.stat().st_size, 'sha256': digest})
assert archives, 'No exact external source inputs captured'
components = []
unpacked = source / 'workdir/UnpackedTarball'
for component in sorted(unpacked.iterdir()):
    if not component.is_dir():
        continue
    notices = []
    for path in sorted(component.rglob('*')):
        relative = path.relative_to(component)
        if (not path.is_file() or path.is_symlink() or len(relative.parts) > 4
                or path.stat().st_size > 1024 * 1024):
            continue
        name = path.name.lower()
        if not any(name.startswith(prefix) for prefix in ('copying', 'license', 'licence', 'notice', 'ofl', 'copyright')):
            continue
        target = package / 'licenses/components' / component.name / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        notices.append(str(target.relative_to(package)))
    components.append({'unpackedBuildInput': component.name, 'noticeFiles': notices})

manifest = {'upstream': json.loads((package / 'source-lock.json').read_text()),
            'externalArchives': archives, 'unpackedComponents': components,
            'scope': 'Exact downloaded/build inputs, including build tools; not a claim that every input is linked into the runtime.',
            'sourceDistribution': 'Preserve these exact archives and the pinned core checkout with CAPT patches when distributing a binary. Source URLs/checksums alone are not a bundled source distribution.'}
(package / 'licenses' / 'source-inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(f'CAPT_OFFICE_SOURCE_INPUTS_CAPTURED archives={len(archives)} components={len(components)}')
