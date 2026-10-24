# Синтетический корпус Word и Excel

Файлы находятся в `app/src/debug/assets/office-fixtures`. Это искусственные
данные проекта без документов пользователя и внешних ресурсов. Их можно
распространять с проектом на условиях его GPLv3. Они попадают только в debug,
а не в release APK. `sha256.json` закрепляет точные проверенные байты.

| Файл | Содержимое и ожидаемое наблюдение будущего конвертера |
| --- | --- |
| `word.docx` | Кириллица/Ё/математические знаки, разные начертания, списки, таблица с объединением, встроенное изображение, верхний/нижний колонтитулы и PAGE, явный разрыв. Три A4-страницы в настольном LibreOffice: две книжные и одна альбомная. |
| `word.doc` | Тот же синтетический документ, преобразованный фильтром MS Word 97 в настоящий CFB. Отдельно проверить сохранение оформления старым импортёром. |
| `workbook.xlsx` | Листы в порядке «Сводка», «Широкая», «Длинная». Объединённые ячейки, границы, числовые форматы, числовые даты с форматом dd.mm.yyyy, формулы с сохранёнными результатами. A4, исходный масштаб 100%, без принудительной одной страницы по высоте. |
| `workbook.xls` | Та же книга, экспортированная фильтром MS Excel 97 в настоящий бинарный Workbook. Наличие сохранённых формул/дат и отображение проверяются движком отдельно от XLSX. |

Сводка: A4/A5 — даты 24/25.10.2026 (Excel serial 46319/46320), D4=50,
D5=30, D6=80. В широкой таблице заняты A:T, последний маркер «КОНЕЦ 2»
в T20. Длинная таблица содержит 180 строк данных, формула каждой суммы
даёт 5; последний маркер «КОНЕЦ 3» находится в E183.
Выбор 3→1 должен давать порядок «Сводка», затем «Длинная», без «Широкой».
При Fit width длинная таблица обязана оставаться многостраничной по высоте.

Проверка целостности без дополнительных Python-зависимостей:

```bash
python3 tools/check-office-fixtures.py
bash tools/check-private-import.sh
```

Первый скрипт проверяет SHA/структуры современного корпуса и сигнатуры/имена
потоков старого. Он **не декодирует** DOC/XLS и не доказывает их рендеринг.
`OfficeIntakeSmoke` на API 29/36 проверяет, что все четыре формата, даже с
ложным именем/MIME PDF, корректно отклоняются текущим приложением и не оставляют
приватные копии/задания. Это регрессия отказа, не приёмка Office-поддержки.

## Повторное создание

Генераторы — исходники в `tools/`. Для Word нужны python-docx 1.2.0 и Pillow;
для XLSX — `@oai/artifact-tool` 2.8.58+ в среде Codex primary runtime;
для DOC/XLS — настольный LibreOffice с указанными фильтрами.
Генерация/проверка корпуса этой работы использовала LibreOfficeDev
26.8.0.0.alpha0 `2c87e51eeaa2b413ff4ae097b2705eea1995d8e5`.
В обычном CI используются сохранённые бинарные файлы; установка офисного
пакета для сборки Android не требуется.

Из корня репозитория (временные каталоги должны быть новыми):

```bash
python3 tools/generate-office-word.py app/src/debug/assets/office-fixtures
```

В среде с primary runtime скопировать `generate-office-workbook.mjs` в
временный каталог, создать там `node_modules` →
`$CODEX_PRIMARY_RUNTIME_NODE_MODULES` и выполнить:

```bash
OUTPUT_DIR=/absolute/repo/app/src/debug/assets/office-fixtures \
QA_DIR=/absolute/temp/office-qa \
"$CODEX_PRIMARY_RUNTIME_NODE" /absolute/temp/generate-office-workbook.mjs
python3 tools/finalize-office-workbook.py app/src/debug/assets/office-fixtures/workbook.xlsx
soffice -env:UserInstallation=file:///absolute/temp/office-profile --headless \
  --convert-to 'doc:MS Word 97' --outdir app/src/debug/assets/office-fixtures \
  app/src/debug/assets/office-fixtures/word.docx
soffice -env:UserInstallation=file:///absolute/temp/office-profile --headless \
  --convert-to 'xls:MS Excel 97' --outdir app/src/debug/assets/office-fixtures \
  app/src/debug/assets/office-fixtures/workbook.xlsx
python3 tools/check-office-fixtures.py --write-manifest
```

Проверить новые документы визуально и просмотреть изменение SHA перед коммитом.
Выходы содержат метаданные времени/версий; регенерация не обещает побайтовой
идентичности с другой версией LibreOffice. Корпус воспроизводит сценарии и
числовые результаты. Сохранённые контрольные суммы выявляют непреднамеренную
подмену. Выводы PNG/PDF и sidecar анализа не добавлять в APK/репозиторий.

Эталонов Microsoft Word/Excel и полного набора паролей/макросов/повреждений
пока нет. Визуальные совпадения с Microsoft Office не заявлены.
