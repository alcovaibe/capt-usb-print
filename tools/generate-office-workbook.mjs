// Synthetic regression data, no user documents. Requires @oai/artifact-tool 2.8.58+.
import fs from 'node:fs/promises';
import path from 'node:path';
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool';

const output = process.env.OUTPUT_DIR;
const qa = process.env.QA_DIR;
if (!output || !qa) throw new Error('Set OUTPUT_DIR and QA_DIR');
await fs.mkdir(output, { recursive: true });
await fs.mkdir(qa, { recursive: true });
const wb = Workbook.create();
const summary = wb.worksheets.add('Сводка');
const wide = wb.worksheets.add('Широкая');
const long = wb.worksheets.add('Длинная');
for (const sheet of [summary, wide, long]) {
  sheet.showGridLines = false;
  sheet.getRange(sheet === wide ? 'A1:T20' : sheet === long ? 'A1:E182' : 'A1:D10').format = {
    font: { name: 'Liberation Sans', size: 11 }, rowHeight: 24, columnWidth: 16,
    borders: { preset: 'all', style: 'thin', color: '#444444' },
  };
  sheet.freezePanes.freezeRows(3);
}
summary.mergeCells('A1:D1');
summary.getRange('A1').values = [['Сводка']];
summary.getRange('A3:D5').values = [
  ['Дата', 'Цена', 'Количество', 'Итого'],
  [(Date.UTC(2026, 9, 24) - Date.UTC(1899, 11, 30)) / 86400000, 12.5, 4, null],
  [(Date.UTC(2026, 9, 25) - Date.UTC(1899, 11, 30)) / 86400000, 10, 3, null],
];
summary.getRange('A4:A5').setNumberFormat('dd.mm.yyyy');
summary.getRange('B4:B5').setNumberFormat('0.00');
summary.getRange('D4:D6').setNumberFormat('0.00');
summary.getRange('D4:D6').formulas = [['=B4*C4'], ['=B5*C5'], ['=SUM(D4:D5)']];
summary.mergeCells('A6:C6');
summary.getRange('A6').values = [['Итого']];
summary.mergeCells('A8:C8');
summary.getRange('A8').values = [['Объединённые ячейки']];
summary.getRange('D8').values = [['КОНЕЦ 1']];
wide.mergeCells('A1:T1');
wide.getRange('A1').values = [['Широкая таблица A T']];
wide.getRange('A1:T20').format.columnWidth = 12;
wide.getRange('A3:T20').values = Array.from({ length: 18 }, (_, row) =>
  Array.from({ length: 20 }, (_, column) => row === 0 ? `СТ ${column + 1}` : (row * 100 + column + 1)));
wide.getRange('T20').values = [['КОНЕЦ 2']];
long.mergeCells('A1:E1');
long.getRange('A1').values = [['Длинная таблица 180 строк']];
long.getRange('A3:E3').values = [['Номер', 'Цена', 'Штук', 'Сумма', 'Метка']];
long.getRange('A4:E183').values = Array.from({ length: 180 }, (_, row) =>
  [row + 1, 2.5, 2, null, row === 179 ? 'КОНЕЦ 3' : `Строка ${row + 1}`]);
long.getRange('A4:E183').format.rowHeight = 24;
long.getRange('D4').formulas = [['=B4*C4']];
long.getRange('D4:D183').fillDown();
long.getRange('B4:D183').setNumberFormat('0.00');
for (const sheet of [summary, wide, long]) {
  sheet.getRange(sheet === wide ? 'A3:T3' : sheet === long ? 'A3:E3' : 'A3:D3').format = {
    fill: '#DDDDDD', font: { bold: true, name: 'Liberation Sans', size: 11 },
  };
}
wb.recalculate();
const values = summary.getRange('D4:D6').values.flat();
if (JSON.stringify(values) !== '[50,30,80]') throw new Error(`Unexpected formula results ${values}`);
for (const sheet of [summary, wide, long]) {
  const preview = await wb.render({ sheetName: sheet.name,
    range: sheet === wide ? 'A1:T6' : sheet === long ? 'A1:E9' : 'A1:D9', scale: 1 });
  await fs.writeFile(path.join(qa, `${sheet.name}.png`), new Uint8Array(await preview.arrayBuffer()));
}
await (await SpreadsheetFile.exportXlsx(wb)).save(path.join(output, 'workbook.xlsx'));
// The exporter emits an analysis sidecar; it is not part of the test corpus or the APK.
await fs.rm(path.join(output, 'workbook.xlsx.inspect.ndjson'), { force: true });
console.log('OFFICE_WORKBOOK_GENERATED D4=50 D5=30 D6=80');
