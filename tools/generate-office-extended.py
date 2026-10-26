#!/usr/bin/env python3
"""Additional synthetic regression documents. python-docx, Pillow, openpyxl; no user input."""
import argparse
from datetime import datetime
from pathlib import Path
import subprocess
import tempfile
from xml.etree import ElementTree as ET
from zipfile import ZipFile

from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Mm, Pt
from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.worksheet.pagebreak import Break


def word(output):
    doc = Document()
    s = doc.sections[0]
    s.page_width, s.page_height = Mm(210), Mm(297)
    s.top_margin = s.bottom_margin = Mm(18)
    s.left_margin, s.right_margin = Mm(23), Mm(17)
    doc.styles['Normal'].font.name = 'Liberation Serif'
    doc.styles['Normal'].font.size = Pt(11)
    s.header.paragraphs[0].text = 'РАСШИРЕННЫЙ КОРПУС — ТАБЛИЦА'
    footer = s.footer.paragraphs[0]
    footer.add_run('Лист ')
    page = OxmlElement('w:fldSimple'); page.set(qn('w:instr'), 'PAGE'); footer._p.append(page)
    doc.add_paragraph('НАЧАЛО ТАБЛИЦЫ')
    table = doc.add_table(rows=1, cols=4)
    table.style = 'Table Grid'
    row = table.rows[0]
    for cell, text in zip(row.cells, ('НОМЕР', 'ОПИСАНИЕ', 'ЦЕНА', 'ПРИМЕЧАНИЕ')):
        cell.text = text
    repeat = OxmlElement('w:tblHeader'); row._tr.get_or_add_trPr().append(repeat)
    for i in range(1, 85):
        cells = table.add_row().cells
        for cell, text in zip(cells, (str(i), f'Позиция {i}: Ёж, съешь ещё булок', f'{i},25', f'МЕТКА-{i:03d}')):
            cell.text = text
        if i == 7:
            cells[1].merge(cells[2]).text = 'ГОРИЗОНТАЛЬНОЕ ОБЪЕДИНЕНИЕ'
    table.cell(10, 3).merge(table.cell(11, 3)).text = 'ВЕРТИКАЛЬНОЕ ОБЪЕДИНЕНИЕ'
    doc.add_paragraph('КОНЕЦ ТАБЛИЦЫ-084')
    doc.add_page_break()
    doc.add_paragraph('ПОСЛЕ РАЗРЫВА')
    p = doc.add_paragraph()
    p.add_run('Индекс ').bold = True
    p.add_run('x').italic = True
    p.add_run('2').font.superscript = True
    p.add_run(' и H')
    p.add_run('2').font.subscript = True
    p.add_run('O; знак минус −; неразрывное число 1\u00a0234,50.')
    for i in range(3):
        doc.add_paragraph(f'Вложенный пункт {i + 1}', 'List Bullet 2')
    doc.save(output / 'word-flow.docx')

    fonts = Document()
    s = fonts.sections[0]
    s.page_width, s.page_height = Mm(210), Mm(297)
    for label, family in [('TIMES', 'Times New Roman'), ('ARIAL', 'Arial'), ('CALIBRI', 'Calibri'),
                          ('CAMBRIA', 'Cambria'), ('COURIER', 'Courier New'), ('NARROW', 'Arial Narrow'),
                          ('MISSING', 'CAPT Missing Font 7D41'), ('BUNDLED', 'Liberation Serif')]:
        p = fonts.add_paragraph()
        r = p.add_run(f'{label}: Кириллица Ёж 0123456789 ± × ÷')
        r.font.name, r.font.size = family, Pt(13)
        r._element.get_or_add_rPr().rFonts.set(qn('w:eastAsia'), family)
    fonts.add_paragraph('КОНЕЦ ШРИФТОВ')
    fonts.save(output / 'word-fonts.docx')


def excel(output):
    wb = Workbook(); wb.remove(wb.active)
    for name in ('Предел', 'Разрывы', 'Высота', 'Области', 'Числа', 'Узкое поле'):
        ws = wb.create_sheet(name)
        ws.sheet_view.showGridLines = False
        ws.sheet_properties.pageSetUpPr.fitToPage = False
        ws.page_setup.paperSize = ws.PAPERSIZE_A4
        ws.page_setup.orientation = 'portrait'
        ws.page_setup.scale = 100
        ws.page_margins.left = ws.page_margins.right = 0.3937
        ws.page_margins.top = ws.page_margins.bottom = 0.5
        ws.oddHeader.center.text = name
        ws.oddFooter.center.text = 'Страница &P'
        for row in ws.iter_rows(min_row=1, max_row=12, max_col=8):
            for c in row:
                c.value = f'{c.row}-{c.column}'
                c.font = Font(name='Liberation Sans', size=11)
                c.border = Border(*(Side(style='thin', color='000000'),) * 4)
                c.alignment = Alignment(vertical='center')
        ws['A1'] = name
        ws.print_options.gridLines = False
        ws.print_area = 'A1:H12'
    ws = wb['Предел']
    for i in range(1, 181):
        col = ws.cell(1, i).column_letter
        ws.column_dimensions[col].width = 80
        ws.cell(2, i).value = f'СТОЛБЕЦ-{i:03d}'
    ws.print_area = 'A1:FX12'
    wb['Разрывы'].col_breaks.append(Break(id=4))
    wb['Разрывы']['H12'] = 'ПРАВЫЙ КРАЙ'
    ws = wb['Высота']; ws.print_area = 'A1:H100'
    for i in range(1, 101):
        ws.row_dimensions[i].height = 24
        ws.cell(i, 1).value = f'СТРОКА-{i:03d}'
    ws['H100'] = 'КОНЕЦ ВЫСОТЫ'
    ws.row_breaks.append(Break(id=30)); ws.row_breaks.append(Break(id=60))
    ws.print_title_rows = '1:1'
    wb['Области'].print_area = ['A1:C12', 'F1:H12']
    ws = wb['Числа']; ws.print_area = 'A1:D14'
    for row in ws.iter_rows():
        for c in row: c.value = None
    for i, (label, value, fmt) in enumerate([
            ('ДАТА', datetime(2024, 2, 29), 'dd.mm.yyyy'),
            ('ПРОЦЕНТ', 0.125, '0.0%'), ('ОТРИЦАТЕЛЬНОЕ', -1234.5, '#,##0.00'),
            ('НАУЧНЫЙ', 125000000, '0.00E+00'), ('НУЛЬ', 0, '0.00'),
            ('ОКРУГЛЕНИЕ', 1.235, '0.00'), ('СУММА', '=SUM(10,20,30)', '0.00'),
            ('УСЛОВИЕ', '=IF(2>1,42,0)', '0.00'), ('ОШИБКА', '=1/0', 'General')], 2):
        ws.cell(i, 1).value = label; ws.cell(i, 3).value = value; ws.cell(i, 3).number_format = fmt
    ws.column_dimensions['A'].width = 25; ws.column_dimensions['C'].width = 22
    ws.merge_cells('A12:D13'); ws['A12'] = 'ОБЪЕДИНЕНИЕ И ПЕРЕНОС\nВТОРАЯ СТРОКА'
    ws['A12'].alignment = Alignment(wrap_text=True)
    ws['A12'].fill = PatternFill('solid', fgColor='DDDDDD')
    ws['D14'] = 'КОНЕЦ ЧИСЕЛ'
    ws = wb['Узкое поле']; ws.print_area = 'A1:A12'
    ws.column_dimensions['A'].width = 255
    ws.page_margins.left = ws.page_margins.right = 4.08  # ~2.7 mm usable, positive.
    ws['A12'] = 'ШИРОКИЙ ОДИНОЧНЫЙ СТОЛБЕЦ'
    wb.save(output / 'workbook-edge.xlsx')
    # Real saved results, including an Excel error, for the cache-preserving importer.
    path = output / 'workbook-edge.xlsx'; patched = path.with_suffix('.tmp')
    ns = {'s': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
    with ZipFile(path) as src, ZipFile(patched, 'w') as dst:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == 'xl/worksheets/sheet5.xml':
                root = ET.fromstring(data)
                for address, value in [('C8', '60'), ('C9', '42'), ('C10', '#DIV/0!')]:
                    c = root.find(f'.//s:c[@r="{address}"]', ns)
                    assert c is not None and c.find('s:f', ns) is not None
                    c.find('s:v', ns).text = value
                    if address == 'C10': c.set('t', 'e')
                data = ET.tostring(root, encoding='utf-8', xml_declaration=True)
            dst.writestr(item, data)
    patched.replace(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('output', type=Path)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    word(args.output); excel(args.output)
    with tempfile.TemporaryDirectory(prefix='capt-extended-lo-') as profile:
        for name, filter_ in [('word-flow.docx', 'doc:MS Word 97'),
                              ('word-fonts.docx', 'doc:MS Word 97'),
                              ('workbook-edge.xlsx', 'xls:MS Excel 97')]:
            subprocess.run(['soffice', f'-env:UserInstallation={Path(profile).as_uri()}',
                            '--headless', '--convert-to', filter_, '--outdir', str(args.output),
                            str(args.output / name)], check=True, timeout=90)
    assert all((args.output / name).stat().st_size > 0 for name in
               ('word-flow.doc', 'word-fonts.doc', 'workbook-edge.xls'))


if __name__ == '__main__':
    main()
