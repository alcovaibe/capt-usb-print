#!/usr/bin/env python3
"""Regression guard: diagnostics must never open the share sheet implicitly."""
from pathlib import Path

main = Path("app/src/main/java/com/cryonum/captusbprint/MainActivity.kt").read_text()
assert main.count("shareReport()") == 2, "shareReport must occur only as button callback and definition"
assert 'button(getString(R.string.share_report)) { shareReport() }' in main
assert "shareAfterScan" not in main and "shareOnComplete" not in main
assert "permissionSharePending" not in main
assert 'recordEvent("PrintTestPage"' in main
assert 'recordEvent("CancelTestPage", "requested"' in main
assert 'recordEvent("PrepareTestPage"' in main
for query in ("GetBasicStatus", "GetExtendedStatus", "GetInputStatus", "GetPrinterInfo"):
    assert query in main
start_of_share = main.index("    private fun shareReport() {")
prefix = main[:start_of_share]
assert "Intent.ACTION_SEND" not in prefix, "Implicit JSON send before explicit sharing"
assert "Intent.createChooser(" not in prefix, "Implicit Android sharing chooser"
assert "startActivity(telegram)" not in prefix, "Implicit Telegram launch"
# Internal app navigation (e.g. to DocumentActivity) is intentionally allowed.
assert "saveDiagnosticSnapshot()" in main and "restoreDiagnosticEvents()" in main
assert 'File(filesDir, "capt-diagnostic-latest.json")' in main
print("Manual-only JSON export guard passed.")

# The separate PDF report must also remain share-only-on-explicit-click.
document = Path("app/src/main/java/com/cryonum/captusbprint/DocumentActivity.kt").read_text()
attempt = Path("app/src/main/java/com/cryonum/captusbprint/FirstPdfPrintAttempt.kt").read_text()
assert document.count("sharePdfReport()") == 2
assert "setOnClickListener { sharePdfReport() }" in document
assert document.count("val send = Intent(Intent.ACTION_SEND)") == 1, "PDF output sharing must have a single explicit implementation"
assert "Intent.createChooser(" not in document[:document.index("    private fun sharePdfReport() {")]
assert "Intent.ACTION_SEND" not in attempt
assert "USB_ATTEMPT_STARTED" in attempt and "automaticRetry" in attempt
print("Explicit PDF-report sharing guard passed.")

# Stage C batch report shares the SAME explicit button, with no implicit send.
batch = Path("app/src/main/java/com/cryonum/captusbprint/PdfBatchPrintAttempt.kt").read_text()
batch_engine = Path("app/src/main/java/com/cryonum/captusbprint/usb/CaptPdfBatchPrintEngine.kt").read_text()
assert "Intent.ACTION_SEND" not in batch and "Intent.createChooser(" not in batch
assert "USB_ATTEMPT_STARTED" in batch and "automaticRetry" in batch
assert batch.index('persist("USB_ATTEMPT_STARTED")') < batch.index("CaptPdfBatchPrintEngine.run(")
document_engine = Path("app/src/main/java/com/cryonum/captusbprint/usb/CaptDocumentPrintEngine.kt").read_text()
assert "OfflinePdfHiscoaPreparation.MAX_COMPRESSED_PAGE_BYTES" in document_engine
assert "if (number == 1 && totalPages > 1)" in document_engine
print("PDF batch explicit-sharing and USB-marker guards passed.")


queue = Path("app/src/main/java/com/cryonum/captusbprint/queue/PrintQueueRuntime.kt").read_text()
assert "Intent.ACTION_SEND" not in queue
assert queue.index('report("USB_ATTEMPT_STARTED")') < queue.index('result = CaptDocumentPrintEngine.run(')
assert 'START_NOT_STICKY' in Path("app/src/main/java/com/cryonum/captusbprint/queue/PrintQueueService.kt").read_text()
print("Queue durable-marker and no-automatic-replay guards passed.")
