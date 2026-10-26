#!/usr/bin/env python3
"""Validate pinned runtime/font bytes and package readable notices in opt-in APK assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(package, root, ndk=None):
    lock = json.loads((root / 'config/office/libreoffice.json').read_text())
    assert json.loads((package / 'source-lock.json').read_text()) == lock
    inventory = json.loads((package / 'inventory.json').read_text())
    for name, entry in inventory['files'].items():
        path = package / name
        assert path.is_file() and path.stat().st_size == entry['bytes'] and digest(path) == entry['sha256'], name
    fonts = json.loads((root / 'config/office-fonts.json').read_text())
    assert fonts['upstream'] == lock['commit']
    directory = package / 'assets/unpack/user/fonts'
    assert {p.name for p in directory.iterdir() if p.is_file()} == {f['file'] for f in fonts['fontFiles']}
    for font in fonts['fontFiles']:
        assert digest(directory / font['file']) == font['sha256'], font['file']
    source = json.loads((package / 'licenses/source-inputs.json').read_text())
    assert source['upstream'] == lock and source['externalArchives'] and source['unpackedComponents']
    assert (package / 'licenses/build-inputs/config_host.mk').is_file()
    assert (package / 'licenses/build-inputs/OpenSymbol/OpenSymbol.sfd').stat().st_size > 1000000
    # All notice text stays outside unpack/: it is for the main-process reader, not native input.
    target = package / 'assets/office-licenses'
    if target.exists(): shutil.rmtree(target)
    shutil.copytree(package / 'licenses', target)
    for name in ('source-lock.json', 'load-policy.patch', 'capt-calc-layout.hxx'):
        shutil.copyfile(package / name, target / name)
    shutil.copyfile(root / 'COPYING', target / 'CAPT-COPYING')
    shutil.copyfile(root / 'config/office-fonts.json', target / 'fonts.json')
    # libc++ is copied by upstream from the NDK, not an external/tarballs input.
    if ndk is None:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        assert sdk, 'Android SDK or --ndk required for exact runtime notices'
        ndk = Path(sdk) / 'ndk' / lock['ndk']
    properties = (ndk / 'source.properties').read_text()
    assert re.search(r'^Pkg.Revision\s*=\s*' + re.escape(lock['ndk']) + r'\s*$', properties, re.M)
    triple = {'arm64-v8a':'aarch64-linux-android', 'armeabi-v7a':'arm-linux-androideabi',
              'x86_64':'x86_64-linux-android'}[inventory['abi']]
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    native = package / 'jniLibs' / inventory['abi']
    assert digest(native / 'libc++_shared.so') == digest(toolchain / 'sysroot/usr/lib' / triple / 'libc++_shared.so')
    for name in ('source.properties', 'NOTICE', 'NOTICE.toolchain',
                 'toolchains/llvm/prebuilt/linux-x86_64/NOTICE',
                 'toolchains/llvm/prebuilt/linux-x86_64/sysroot/NOTICE',
                 'toolchains/llvm/prebuilt/linux-x86_64/clang_source_info.md'):
        destination = target / 'ndk' / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ndk / name, destination)
    bundled = {p.name for p in native.glob('*.so')}
    platform = {'libGLESv2.so','libandroid.so','libc.so','libdl.so','libjnigraphics.so','liblog.so','libm.so','libz.so'}
    libraries = []
    for path in sorted(native.glob('*.so')):
        dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
        needed = sorted(re.findall(r'\(NEEDED\).*?\[(.*?)\]', dynamic))
        assert set(needed) <= bundled | platform, (path.name, needed)
        libraries.append({'file':path.name, 'preStripSha256':digest(path), 'preStripBytes':path.stat().st_size,
                          'bundledDependencies':[n for n in needed if n in bundled],
                          'androidPlatformDependencies':[n for n in needed if n in platform]})
    (target / 'native-runtime.json').write_text(json.dumps({'abi':inventory['abi'],
        'ndkVersion':lock['ndk'], 'libraries':libraries,
        'scope':'Native package before AGP symbol stripping: DT_NEEDED shared libraries; static components remain documented in LICENSE and build inputs.',
        'libcxxSource':'https://android.googlesource.com/toolchain/llvm-project/; exact revisions in ndk/toolchains/llvm/prebuilt/linux-x86_64/clang_source_info.md'},indent=2)+'\n')
    text = (target / 'LICENSE').read_text()
    for term in ('Mozilla Public License Version 2.0', 'SIL Open Font License',
                 'LIBERATION FONT SOFTWARE', 'Caladea', 'Carlito', 'Gentium'):
        assert term in text, term
    intro = ('CAPT USB Print — локальный LibreOffice\n\n'
             'Исходники CAPT, сборочные сценарии и изменения движка:\nhttps://github.com/alcovaibe/capt-usb-print\n'
             f'Исходники LibreOffice: https://github.com/LibreOffice/core/tree/{lock["commit"]}\n\n'
             'LICENSE содержит полный текст уведомлений именно собранного LibreOffice. '
             'COPYING.MPL/COPYING.LGPL/COPYING и CAPT-COPYING сохраняют исходные условия. '
             'fonts.json перечисляет точные файлы и SHA-256 шрифтов, их правообладателей, условия и источники. '
             'OpenSymbol: LGPLv3+, исходный SFD включён в build-inputs/OpenSymbol. '
             'ndk/ сохраняет оригинальные уведомления Android NDK, включая libc++; '
             'native-runtime.json сверяет фактические зависимости и SHA библиотек. '
             'Liberation Sans Narrow: отдельные условия Liberation с исключениями, не OFL. '
             'Шрифты поставлены без изменения байтов и переименования.\n\n'
             'source-inputs.json/config_host.mk/download.lst фиксируют входы сборки, включая инструменты. '
             'Это не утверждение, что каждый вход включён в нативную библиотеку. '
             'Для распространения бинарного выпуска необходимо предоставить соответствующие исходники '
             'и инструкции повторной сборки; одни ссылки и контрольные суммы не заменяют такой комплект.\n')
    (target / 'ABOUT.txt').write_text(intro)
    index = []
    for path in sorted(target.rglob('*')):
        if path.is_file():
            index.append({'file':str(path.relative_to(target)), 'sha256':digest(path),'bytes':path.stat().st_size})
    (target / 'index.json').write_text(json.dumps(index,ensure_ascii=False,indent=2)+'\n')
    print(f'CAPT_OFFICE_NOTICES_PREPARED files={len(index)} fonts={len(fonts["fontFiles"])}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('package',type=Path)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--ndk',type=Path)
    args = parser.parse_args(); prepare(args.package,args.root,args.ndk)
