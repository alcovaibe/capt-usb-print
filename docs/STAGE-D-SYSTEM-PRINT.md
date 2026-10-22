# Stage D — Android system printing

Base: owner-merged Stage C PR #25, `130c158f8288`; new private PR #26.
Main CI 123 passed permanent signing and API 36 upgrade. Stage D is implemented
in the feature branch; its CI and physical HONOR acceptance must be recorded
separately. No physical Stage D result has been supplied.

## Flow and limits

Enable the CAPT USB Print service via the standard Android print-settings screen.
MainActivity is also the service settings/printer-info destination for USB grants.
Discovery only enumerates USB: stable local ID `canon-lbp2900-usb`, exactly one
Canon 04A9:2676, no serial lookup or CAPT probe. Unavailable includes missing,
multiple or ungranted devices. Availability does not claim the printer has paper
or is ready to accept a CAPT reservation; the existing runner checks live status.

Advertise A4, 600 dpi, monochrome and simplex; provisional 433 mil margins
correspond to C's 260 px software allowance and are not measured hardware limits.
Accept Android's A4 mil rounding (8270×11690) and exact-mm rounding (8268×11693).
Android/app layout handles those margins before delivery. The system raster path
preserves 600/72 point scale with no second FIT/inset; landscape only rotates
the logical sheet into the unchanged accepted portrait CAPT grid. Non-A4 page
dimensions fail during preparation, before any USB job starts.

PrintService/PrintJob/PrintDocument API access is on the main thread. File/pipe
reading, PDF inspection, rasterization and USB are off-main. Pipe intake closes
the descriptor on every path, polls at most 250 ms per wait, times out after
30 seconds and rejects >64 MiB. Incoming sources and compressed bands share the
existing 256 MiB queue budget plus 8 MiB headroom. The four-job admission limit
includes concurrent incoming source reservations. A pending Android job may
block before intake for USB permission, queue capacity or explicit queue review.

Both entry points use the same DocumentQueue/PrintQueueRuntime and one serial
PrintQueueWorker. The system-bound service relies on Android's active-job binding,
without starting a background foreground service. Foreground teardown cancels
direct jobs only; service disable/disconnect cancels only its own jobs. Cancel-all
also invalidates incoming pipes and pre-intake Android handles; completion of a
previously accepted copy cannot reactivate cancelled work.

## Page selection and copies

Standard Android PrintSpooler adjusts PrintJob document count/ranges to the
delivered file and may physically remove unselected pages, then set ALL_PAGES.
The original page numbers are not necessarily available to a print service.
The resolver therefore treats consistent declared/PDF counts and ranges as file
coordinates, never as proof that the file is the entire original document.
ALL_PAGES prints the delivered PDF once. A reduced file with inconsistent
original metadata is supported only when its count equals the normalized
selection count. Ambiguous metadata, invalid/oversized ranges or inconsistent
ALL_PAGES counts are rejected before USB. Do not guess a mapping for a partial
cached superset. JSON records the actual request and resulting spool indices.

References reviewed:
- [Android PrintDocument](https://developer.android.com/reference/android/printservice/PrintDocument)
- [Android PrintService lifecycle/thread requirements](https://developer.android.com/reference/android/printservice/PrintService)
- [Android PrintJob state APIs](https://developer.android.com/reference/android/printservice/PrintJob)
- [AOSP RemotePrintDocument file-relative page tracking](https://android.googlesource.com/platform/frameworks/base/+/177eda822164aee78245a3d474f3a10aa9f2bd64/packages/PrintSpooler/src/com/android/printspooler/model/RemotePrintDocument.java)
- [AOSP PrintActivity count/range adjustment and final PDF shredding](https://android.googlesource.com/platform/frameworks/base/+/61209bc/packages/PrintSpooler/src/com/android/printspooler/ui/PrintActivity.java)

Copies expand once in JobSpecification, collated, reusing prepared page files.
No copy field is passed into CAPT as another multiplier. C bounds remain:
1000 source pages, 2000 sheets and 20 copies; unsupported requests fail without
truncation. The application never sends its direct print path back into itself
through PrintManager.

## Outcomes, cancellation and recovery

Android job starts/blocks while obtaining its source and waiting for prerequisites.
Queue state and confirmed counters drive system status/progress. Only complete
printer-counter confirmation maps to completed. A safe pre-print cancellation
maps to cancelled. Uncertain USB outcomes map to failed with an explicit unknown
result message, including after cancellation; accepted sheets can still emerge.
An uncertain/failed USB job pauses later queue work until explicit inspection.

Tags persist before reading and before activation. On rebind, tagged attempts
reconcile against durable queue history; their PDFs are not re-read or replayed.
After interruption, inspect the output and create a new job for the desired
remaining pages. Android's retry of the old tagged job does not silently reprint.
Known ReserveUnit 0x8C remains unresolved; no reset, heartbeat or blind retry added.

System routing/request/error history is bounded to 24 private events. A queue
screen button explicitly exports it; per-job CAPT JSON has its own share button.
Neither includes labels, document URIs, content/raster/hashes or printer serials.
If HONOR does not route a job through Android PrintService, absence of a received
job in this journal plus a second-source result helps distinguish that path.

## Owner hardware check after reviewed CI and a signed release

1. Update the signed APK in place; connect one LBP2900, enable it and grant USB
   access in CAPT USB Print. Open the system-print settings button and enable the
   CAPT USB Print service.
2. In HONOR Documents open a known PDF with at least three pages. Use system
   Print, choose Canon LBP2900, A4 portrait, pages 2–3 and two copies.
   Expected: four sheets in order 2, 3, 2, 3, retaining source layout.
3. From a second app with Android system Print, send one known page, one copy.
   Expected: one sheet and a separate job in the same CAPT queue.
4. With the printer disconnected, try submitting a new system job if Android
   permits it, then cancel it in Android's print-job UI. Expected: unavailable
   or blocked/cancelled without USB traffic. Reconnecting must not replay a
   cancelled/interrupted job. Do not reproduce a mechanical fault.
5. Send the system-print JSON and the two per-job JSON reports from the queue
   screen; state the app names, actual sheet count/order and any clipping/scale
   issue. If a job fails or has unknown output, stop and send those reports before
   another attempt.

CI/emulator checks cannot establish HONOR routing, physical sheet quality or
MagicOS lifecycle behavior. Do not mark Stage D accepted without the owner's
result for HONOR Documents and the second source.
