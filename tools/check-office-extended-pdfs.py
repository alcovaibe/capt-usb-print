#!/usr/bin/env python3
"""Verify only fixed synthetic Office evidence. Poppler XML records actual PDF fonts/coordinates."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
from xml.etree import ElementTree as ET


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path); parser.add_argument('report', type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    assert report['syntheticOnly'] is True and report['hardwarePrintTest'] is False
    rows = {r['case']:r for r in report['attempts']}
    assert len(rows) == 30
    for kind in ('xls','xlsx'):
        for suffix in ('minimum','single-column','areas','all-rejected'):
            assert rows[f'{kind}-{suffix}']['failure'] == 'FIT_WIDTH_UNSUPPORTED'
        assert rows[f'{kind}-next']['pages'] == 1
    expected = {f'{name}.pdf':r for name,r in rows.items() if r['failure'] is None}
    texts, raw_texts, xmls, fonts = {}, {}, {}, {}
    with tarfile.open(args.archive,'r:') as archive, tempfile.TemporaryDirectory(prefix='capt-extended-') as directory:
        for member in archive.getmembers():
            if member.isdir():
                assert member.name.rstrip('/') == 'office-extended-pdfs'; continue
            path = Path(member.name)
            assert member.isfile() and len(path.parts) == 2 and path.parts[0] == 'office-extended-pdfs'
            assert path.name in expected and path.name not in texts and 0 < member.size <= 64*1024*1024
            pdf = Path(directory)/'synthetic.pdf'; pdf.write_bytes(archive.extractfile(member).read())
            text = subprocess.run(['pdftotext','-layout',str(pdf),'-'],check=True,capture_output=True,text=True,timeout=30).stdout
            pages = text.split('\f'); assert not pages.pop().strip()
            assert len(pages) == expected[path.name]['pages']
            texts[path.name] = [' '.join(p.split()) for p in pages]
            # Layout mode interleaves neighbouring columns across a wrapped
            # merged-cell label. Content-stream order keeps the cell together.
            raw_texts[path.name] = ' '.join(subprocess.run(
                ['pdftotext','-raw',str(pdf),'-'],check=True,capture_output=True,
                text=True,timeout=30).stdout.split())
            xml = subprocess.run(['pdftohtml','-xml','-i','-stdout',str(pdf)],check=True,
                                 capture_output=True,text=True,timeout=30).stdout
            root = ET.fromstring(xml); xmls[path.name] = root
            font_rows = subprocess.run(['pdffonts',str(pdf)],check=True,capture_output=True,text=True,timeout=30).stdout
            fonts[path.name] = font_rows
            for page in root.findall('page'):
                width, height = float(page.get('width')), float(page.get('height'))
                for span in page.findall('text'):
                    left,top = float(span.get('left')),float(span.get('top'))
                    right,bottom = left+float(span.get('width')),top+float(span.get('height'))
                    assert left >= -2 and top >= -2 and right <= width+2 and bottom <= height+2, 'Text outside A4'
    assert set(texts) == set(expected)
    observed = {}
    for kind in ('docx','doc'):
        pages = texts[f'flow-{kind}.pdf']; combined = ' '.join(pages)
        assert len(pages) >= 3
        assert 'НАЧАЛО ТАБЛИЦЫ' in pages[0] and 'ПОСЛЕ РАЗРЫВА' in pages[-1]
        assert 'КОНЕЦ ТАБЛИЦЫ-084' in combined
        assert 'ГОРИЗОНТАЛЬНОЕ ОБЪЕДИНЕНИЕ' in raw_texts[f'flow-{kind}.pdf']
        assert 'ВЕРТИКАЛЬНОЕ ОБЪЕДИНЕНИЕ' in raw_texts[f'flow-{kind}.pdf']
        assert 'МЕТКА-001' in combined and 'МЕТКА-084' in combined
        for number,page in enumerate(pages,1):
            assert 'РАСШИРЕННЫЙ КОРПУС' in page and re.search(rf'Лист\s+{number}\b',page)
            if 'МЕТКА-' in page: assert 'НОМЕР' in page and 'ОПИСАНИЕ' in page
        name = f'fonts-{kind}.pdf'; font_map = {}
        root = xmls[name]
        for page in root.findall('page'):
            definitions = {f.get('id'):f.get('family') for f in page.findall('fontspec')}
            for span in page.findall('text'):
                content = ''.join(span.itertext())
                for label in ('TIMES','ARIAL','CALIBRI','CAMBRIA','COURIER','NARROW','MISSING','BUNDLED'):
                    if content.startswith(label+':'): font_map[label] = definitions[span.get('font')]
        assert len(font_map) == 8, font_map
        # These labels are Latin; Cyrillic can use a different fallback, especially in Caladea.
        for label,family in {'TIMES':'LiberationSerif','ARIAL':'LiberationSans','COURIER':'LiberationMono',
                             'CALIBRI':'Carlito','CAMBRIA':'Caladea','NARROW':'LiberationSansNarrow','BUNDLED':'LiberationSerif'}.items():
            normalized = re.sub(r'[^a-z]','',font_map[label].lower())
            assert family.lower() in normalized, (kind,label,font_map[label])
        assert 'missing' not in font_map['MISSING'].lower()
        assert 'КОНЕЦ ШРИФТОВ' in ' '.join(texts[name]) and 'Кириллица' in ' '.join(texts[name])
        observed[kind] = font_map
    for kind in ('xls','xlsx'):
        tall = texts[f'{kind}-tall.pdf']; subset = texts[f'{kind}-subset.pdf']
        assert len(tall)>1 and 'КОНЕЦ ВЫСОТЫ' in tall[-1]
        assert 'КОНЕЦ ВЫСОТЫ' in subset[-2] and 'КОНЕЦ ЧИСЕЛ' in subset[-1]
        assert len(subset) == len(tall)+1
        for suffix in ('numbers-fit','numbers-saved','next'):
            page = ' '.join(texts[f'{kind}-{suffix}.pdf'])
            for term in ('29.02.2024','12.5%','ОТРИЦАТЕЛЬНОЕ','ОБЪЕДИНЕНИЕ','ВТОРАЯ СТРОКА','КОНЕЦ ЧИСЕЛ'):
                assert term in page.replace('12,5%','12.5%'), (kind,suffix,term)
            assert re.search(r'60[.,]00',page) and re.search(r'42[.,]00',page) and '#DIV/0!' in page
            assert re.search(r'[-−]1[\s,\u00a0]*234[.,]50',page)
            assert re.search(r'1[.,]25E\+0?8',page)
            assert re.search(r'1[.,]24\b',page)
        assert len(texts[f'{kind}-manual-saved.pdf']) >= 2
        fitted = texts.get(f'{kind}-manual-fit.pdf')
        if fitted is not None: assert len(fitted) == 1 and 'ПРАВЫЙ КРАЙ' in fitted[0]
    Path('office-font-fallback-observed.json').write_text(json.dumps({
        'syntheticOnly':True,'actualPdfLatinLabelFonts':observed,
        'allGlyphRunsVerified':False,'microsoftOfficeComparison':False},ensure_ascii=False,indent=2)+'\n')
    for kind in ('docx','doc','xls','xlsx'):
        name=f'upstream-{kind}.pdf'
        assert name in texts and len(texts[name])>=1 and any(page.strip() for page in texts[name])
    print(f'CAPT_OFFICE_EXTENDED_PDF_PASSED pdfs={len(texts)} attempts=30 (generated and upstream corpus, no USB)')


if __name__ == '__main__': main()
