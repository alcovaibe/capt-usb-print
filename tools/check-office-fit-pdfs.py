#!/usr/bin/env python3
"""Inspect ONLY the fixed synthetic PDF evidence from OfficeWorkerSmoke, without extraction."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile


def check_word(pages, image_count):
    pages = [' '.join(page.split()) for page in pages]
    assert len(pages) == 3 and image_count >= 1
    for number, page in enumerate(pages, 1):
        assert 'синтетический тест кириллицы' in page
        assert re.search(rf'Страница\s+{number}\b', page), 'Word PAGE field/header/footer must survive'
    for number, title in enumerate(('ПЕРВАЯ СТРАНИЦА', 'ВТОРАЯ СТРАНИЦА', 'ТРЕТЬЯ СТРАНИЦА')):
        assert title in pages[number], 'Explicit Word page/section break must survive'
        assert all(title not in page for index, page in enumerate(pages) if index != number)
    assert 'Полужирный.' in pages[0] and 'Курсив.' in pages[0] and 'Подчёркнутый.' in pages[0]
    assert 'Первый пункт списка' in pages[0] and 'Маркированный пункт' in pages[0]
    assert 'Объединённые ячейки' in pages[0] and 'Ячейка 3 3' in pages[0]
    assert 'Ёж, съешь ещё' in pages[1] and 'Альбомный раздел A4' in pages[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    assert report['syntheticOnly'] is True and report['hardwarePrintTest'] is False
    assert report['workerProcesses'] == 16 and len(report['conversions']) == 16
    expected = {}
    for row in report['conversions']:
        assert row['format'] in ('docx', 'doc', 'xls', 'xlsx')
        assert row['selectedSheets'] in ('all', '1', '2', '3', '1+3')
        assert isinstance(row['fitWidth'], bool)
        name = f"{row['format']}-{'fit' if row['fitWidth'] else 'saved'}-{row['selectedSheets']}.pdf"
        assert name not in expected
        expected[name] = row['pdfPages']
    texts = {}
    word_images = {}
    with tarfile.open(args.archive, 'r:') as archive:
        members = archive.getmembers()
        assert len(members) == len(expected) + 1
        for member in members:
            if member.isdir():
                assert member.name.rstrip('/') == 'office-layout-smoke'
                continue
            assert member.isfile() and 0 < member.size <= 64 * 1024 * 1024
            path = Path(member.name)
            assert len(path.parts) == 2 and path.parts[0] == 'office-layout-smoke'
            assert path.name in expected and path.name not in texts
            content = archive.extractfile(member).read()
            assert content.startswith(b'%PDF-')
            # No tar extraction/path traversal. Poppler sees one bounded synthetic PDF at a time.
            with tempfile.TemporaryDirectory(prefix='capt-fit-pdf-') as directory:
                pdf = Path(directory) / 'synthetic.pdf'
                pdf.write_bytes(content)
                text = subprocess.run(['pdftotext', '-layout', '-enc', 'UTF-8', str(pdf), '-'],
                    check=True, capture_output=True, text=True, timeout=30).stdout
                if path.name in ('docx-saved-all.pdf', 'doc-saved-all.pdf'):
                    images = subprocess.run(['pdfimages', '-list', str(pdf)],
                        check=True, capture_output=True, text=True, timeout=30).stdout
                    # Poppler lists page, image number, type. This is image presence, not visual QA.
                    word_images[path.name] = len(re.findall(r'^\s*\d+\s+\d+\s+image\s', images, re.M))
            pages = text.split('\f')
            assert not pages[-1].strip()
            pages = pages[:-1]
            assert len(pages) == expected[path.name]
            texts[path.name] = pages
    assert set(texts) == set(expected)
    for kind in ('docx', 'doc'):
        name = f'{kind}-saved-all.pdf'
        check_word(texts[name], word_images[name])
    for kind in ('xls', 'xlsx'):
        wide = texts[f'{kind}-fit-2.pdf']
        assert len(wide) == 1
        assert re.search(r'СТ\s*1\b', wide[0]) and re.search(r'СТ\s*20\b', wide[0])
        assert re.search(r'КОНЕЦ\s*2', wide[0]), 'Last column must survive fit-to-width'
        saved = texts[f'{kind}-saved-2.pdf']
        assert len(saved) == 3 and re.search(r'КОНЕЦ\s*2', '\n'.join(saved))
        long = texts[f'{kind}-fit-3.pdf']
        assert len(long) > 1 and re.search(r'КОНЕЦ\s*3', long[-1])
        summary = ' '.join(' '.join(texts[f'{kind}-fit-1.pdf']).split())
        assert 'Сводка' in summary and 'Объединённые ячейки' in summary
        assert '24.10.2026' in summary and '25.10.2026' in summary
        assert re.search(r'50[.,]00', summary) and re.search(r'80[.,]00', summary)
        assert 'B4*C4' not in summary, 'Displayed results expected, not formula source'
        subset = texts[f'{kind}-fit-1+3.pdf']
        all_pages = texts[f'{kind}-fit-all.pdf']
        assert 'Сводка' in subset[0] and re.search(r'КОНЕЦ\s*3', subset[-1])
        assert len(subset) == len(texts[f'{kind}-fit-1.pdf']) + len(long)
        assert len(all_pages) == len(subset) + len(wide)
    print('CAPT_OFFICE_FIT_PDF_CONTENT_PASSED (16 synthetic PDFs; Word breaks/headers/PAGE/image; Excel right edge/dates/results/order; no USB)')


if __name__ == '__main__':
    main()
