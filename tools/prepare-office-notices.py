#!/usr/bin/env python3
"""Validate pinned runtime/font bytes and package readable notices in opt-in APK assets."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(package, root):
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
    args = parser.parse_args(); prepare(args.package,args.root)
