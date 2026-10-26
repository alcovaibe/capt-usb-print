#!/usr/bin/env python3
"""Maintainer-only reproduction of the four public TDF regression inputs; not app networking."""
import hashlib
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root/'config/office-upstream-fixtures.json').read_text())
target = root/'app/src/debug/assets/office-fixtures'
for row in manifest['files']:
    assert Path(row['file']).name==row['file'] and '..' not in Path(row['path']).parts
    url='https://raw.githubusercontent.com/LibreOffice/core/'+manifest['commit']+'/'+row['path']
    with urllib.request.urlopen(url,timeout=60) as source:
        data=source.read(100001)
    assert 0<len(data)<=100000 and hashlib.sha256(data).hexdigest()==row['sha256']
    assert hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==row['gitBlob']
    (target/row['file']).write_bytes(data)
print('CAPT_PINNED_UPSTREAM_FIXTURES_FETCHED count=4')
