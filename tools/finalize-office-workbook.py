#!/usr/bin/env python3
"""Set deterministic A4 print metadata missing from the workbook builder's API surface."""
import argparse
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

S = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
ET.register_namespace('', S)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('workbook', type=Path)
    args = parser.parse_args()
    replacement = args.workbook.with_suffix('.tmp')
    try:
        with ZipFile(args.workbook) as source, ZipFile(replacement, 'w') as target:
            for item in source.infolist():
                data = source.read(item.filename)
                if item.filename.startswith('xl/worksheets/sheet'):
                    sheet = ET.fromstring(data)
                    for tag in ('pageMargins', 'pageSetup'):
                        for old in sheet.findall(f'{{{S}}}{tag}'):
                            sheet.remove(old)
                    ET.SubElement(sheet, f'{{{S}}}pageMargins', {
                        'left': '0.3937', 'right': '0.3937', 'top': '0.3937',
                        'bottom': '0.3937', 'header': '0.2', 'footer': '0.2'})
                    ET.SubElement(sheet, f'{{{S}}}pageSetup', {
                        'paperSize': '9', 'orientation': 'portrait', 'scale': '100',
                        'fitToWidth': '0', 'fitToHeight': '0'})
                    data = ET.tostring(sheet, encoding='utf-8', xml_declaration=True)
                target.writestr(item, data)
        replacement.replace(args.workbook)
    finally:
        replacement.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
