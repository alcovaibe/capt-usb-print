#!/usr/bin/env python3
"""Measure the CI-only built APK, never Office document contents or user files."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as archive:
        entries = [item for item in archive.infolist() if not item.is_dir()]
        assert len(entries) < 65536 and len({item.filename for item in entries}) == len(entries)
        assert any(item.filename == 'lib/x86_64/liblo-native-code.so' for item in entries), 'Opt-in Office APK required'
        assert any(item.filename.startswith('assets/unpack/') for item in entries)
        notice_index = json.loads(archive.read('assets/office-licenses/index.json'))
        for notice in notice_index:
            data = archive.read('assets/office-licenses/' + notice['file'])
            assert len(data) == notice['bytes'] and hashlib.sha256(data).hexdigest() == notice['sha256']
        assert {'ABOUT.txt','LICENSE','COPYING.MPL','COPYING.LGPL','CAPT-COPYING','fonts.json','source-inputs.json','native-runtime.json','ndk/NOTICE.toolchain'} <= {n['file'] for n in notice_index}
        runtime = json.loads(archive.read('assets/office-licenses/native-runtime.json'))
        packaged_runtime = []
        for library in runtime['libraries']:
            name = f'lib/{runtime["abi"]}/{library["file"]}'
            data = archive.read(name)
            packaged_runtime.append({'file':name,'apkBytes':len(data),'apkSha256':hashlib.sha256(data).hexdigest(),
                                     'preStripSha256':library['preStripSha256']})
        def size(items):
            return {'files': len(items), 'uncompressedBytes': sum(item.file_size for item in items),
                    'zipPayloadBytes': sum(item.compress_size for item in items)}
        native = {}
        for abi in ('armeabi-v7a', 'arm64-v8a', 'x86_64'):
            files = [item for item in entries if item.filename.startswith(f'lib/{abi}/')]
            native[abi] = {**size(files), 'officeCorePresent': any(item.filename.endswith('/liblo-native-code.so') for item in files)}
        unpack = [item for item in entries if item.filename.startswith('assets/unpack/')]
        fonts = [item for item in entries if Path(item.filename).suffix.lower() in ('.ttf', '.otf', '.ttc')]
        font_metadata = [{ 'asset': item.filename, 'bytes': item.file_size } for item in sorted(fonts, key=lambda item: item.filename)]
    with args.apk.open('rb') as content:
        digest = hashlib.file_digest(content, 'sha256').hexdigest()
    result = {'ciOnlyDebugApk': True, 'releaseSizeMeasurement': False,
              'installedSizeMeasurement': False, 'ramMeasurement': False,
              'apkBytes': args.apk.stat().st_size, 'apkSha256': digest,
              'zipEntries': size(entries), 'nativeLibrariesByAbi': native,
              'officeRuntimeAfterAgpPackaging':packaged_runtime,
              'workerEngineUnpackAssets': size(unpack), 'fontFiles': font_metadata,
              'limits': 'ZIP payload excludes ZIP/signing overhead. Native totals include CAPT libraries. Unpack assets exclude runtime profile/cache/source/PDF. Font filenames do not prove family coverage or license acceptance.'}
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'CAPT_OFFICE_PROBE_APK_MEASURED bytes={result["apkBytes"]} unpackAssets={result["workerEngineUnpackAssets"]["uncompressedBytes"]} fonts={len(fonts)} (debug only; not installed size/RAM/release)')


if __name__ == '__main__':
    main()
