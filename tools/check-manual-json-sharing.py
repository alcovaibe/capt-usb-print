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
assert "startActivity(" not in main[:start_of_share], "Only explicit share handler may open another app"
assert "saveDiagnosticSnapshot()" in main and "restoreDiagnosticEvents()" in main
assert 'File(filesDir, "capt-diagnostic-latest.json")' in main
print("Manual-only JSON export guard passed.")
