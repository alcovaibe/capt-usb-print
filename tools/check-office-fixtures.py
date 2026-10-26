#!/usr/bin/env python3
"""Check synthetic corpus identity/structure. Does not test Office rendering in Android."""
import argparse
import hashlib
import json
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1] / 'app/src/debug/assets/office-fixtures'
FILES = ('word.docx', 'word.doc', 'workbook.xls', 'workbook.xlsx',
         'word-flow.docx', 'word-flow.doc', 'word-fonts.docx', 'word-fonts.doc',
         'workbook-edge.xlsx', 'workbook-edge.xls')
S = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
W = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write-manifest', action='store_true')
    args = parser.parse_args()
    identity = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in FILES}
    manifest = ROOT / 'sha256.json'
    if args.write_manifest:
        manifest.write_text(json.dumps(identity, indent=2) + '\n', encoding='utf-8')
    assert identity == json.loads(manifest.read_text(encoding='utf-8')), 'Fixture identity changed'
    with ZipFile(ROOT / 'word-flow.docx') as package:
        tree = ET.fromstring(package.read('word/document.xml'))
        assert tree.find('.//w:tblHeader', W) is not None
        assert tree.find('.//w:vMerge', W) is not None
        assert len(tree.findall('.//w:tr', W)) == 85
    with ZipFile(ROOT / 'word-fonts.docx') as package:
        assert b'CAPT Missing Font 7D41' in package.read('word/document.xml')
    with ZipFile(ROOT / 'workbook-edge.xlsx') as package:
        assert b'colBreaks' in package.read('xl/worksheets/sheet2.xml')
        assert b'rowBreaks' in package.read('xl/worksheets/sheet3.xml')
        cells = ET.fromstring(package.read('xl/worksheets/sheet5.xml'))
        assert cells.find('.//s:c[@r="C8"]/s:v', S).text == '60'
        assert cells.find('.//s:c[@r="C9"]/s:v', S).text == '42'
        assert cells.find('.//s:c[@r="C10"]/s:v', S).text == '#DIV/0!'
    for name, stream in [('word.doc', 'WordDocument'), ('workbook.xls', 'Workbook')]:
        data = (ROOT / name).read_bytes()
        assert data.startswith(bytes.fromhex('D0CF11E0A1B11AE1'))
        assert (stream + '\0').encode('utf-16-le') in data
    with ZipFile(ROOT / 'word.docx') as package:
        doc = ET.fromstring(package.read('word/document.xml'))
        assert doc.find('.//w:br[@w:type="page"]', W) is not None
        assert doc.find('.//w:gridSpan', W) is not None
        assert doc.find('.//w:drawing', W) is not None
        assert doc.find('.//w:pgSz[@w:orient="landscape"]', W) is not None
        assert 'word/header1.xml' in package.namelist()
        assert 'PAGE' in package.read('word/footer1.xml').decode()
    with ZipFile(ROOT / 'workbook.xlsx') as package:
        workbook = ET.fromstring(package.read('xl/workbook.xml'))
        assert [sheet.get('name') for sheet in workbook.findall('s:sheets/s:sheet', S)] == [
            'Сводка', 'Широкая', 'Длинная']
        first = ET.fromstring(package.read('xl/worksheets/sheet1.xml'))
        cells = {c.get('r'): c for c in first.findall('.//s:c', S)}
        for address, expected in [('D4', '50'), ('D5', '30'), ('D6', '80')]:
            assert cells[address].find('s:f', S) is not None
            assert cells[address].find('s:v', S).text == expected
        assert cells['A4'].get('t') == 'n' and cells['A4'].find('s:v', S).text == '46319'
        assert first.find('.//s:mergeCell[@ref="A8:C8"]', S) is not None
        for index, last in [(2, 'T20'), (3, 'E183')]:
            sheet = ET.fromstring(package.read(f'xl/worksheets/sheet{index}.xml'))
            assert sheet.find(f'.//s:c[@r="{last}"]', S) is not None
            assert sheet.find('s:pageSetup', S).get('paperSize') == '9'
        styles = ET.fromstring(package.read('xl/styles.xml'))
        assert styles.find('.//s:numFmt[@formatCode="dd.mm.yyyy"]', S) is not None
    print('OFFICE_CORPUS_INTEGRITY_PASSED (not an Android rendering test)')


if __name__ == '__main__':
    main()
