# Queue, cancellation and paper-out recovery

Implementation baseline: main `69d10642874d0d6bdd411d4dc0d73b8fc526aa1d`
(signed build 147). This is a new user-requested change; merge/release remains
an owner action. Protocol sources are pinned at
[`ValdikSS/captdriver@892f320c019974a5b737e000fcc2c8582264a131`](https://github.com/ValdikSS/captdriver/tree/892f320c019974a5b737e000fcc2c8582264a131).

## Evidence and limits

The owner physically printed 11 pages from HONOR Documents, a screenshot,
a selected document page and a test sheet on build 147. Supplied
`capt-queue-3453670785821991103.json` confirms three successful document jobs:
11 system-PDF sheets and two single-sheet JPEG jobs, with acknowledged END
and ReleaseUnit. This is physical owner testimony plus corresponding wire/
counter evidence, not an emulator result. The test sheet's physical output
is owner-reported; its cancelled attempt did not obtain a completion checkpoint.

`capt-diagnostic-1721637016193938057.json` shows the separate test JobID=4:
23,044 print bytes, StartPrint(1), user cancel at 15:51:46.363, then USB close
without END/Release. After reconnect,
`capt-queue-5096530881151047118.json` and
`capt-diagnostic-5503370876703577279.json` show three JPEG jobs and another test
refused by ReserveUnit (`A0 A2 08 00 8C 00 00 00`), with no valid JobID/raster.
Power off/on restored printing according to the owner. An abandoned reservation
is a supported hypothesis, not a complete proven decoding of `8C`. This is
separate from the old GetBasicStatus `A0 E0 06 00 88 00` preflight rejection.

The new cancellation, global clear and paper-out continuation have **not been
physically accepted yet**. Pinned `src/prn_lbp2900.c:lbp2900_cancel_cleanup`
sends SetLEDStatus(12 zero bytes), JobInfo ABORT=4 and ReleaseUnit. The pinned
`info/lbp3000-protocol.md` itself labels flag 4 *unconfirmed*. We follow that
specific procedure only for an owned live job and require every ACK; rejection
or missing reply leaves an uncertain result, never a claimed clear.

## Cancellation

`CaptDocumentPrintEngine` owns the lifecycle for documents, PDF pilot, one-sheet
and two-sheet tests. Soft cancellation waits for the current complete CAPT
frame/query reply; it does not interrupt a transport write/read. Between
exchanges it stops new raster/StartPrint commands and runs:

1. If JobInfo START was accepted: SetLEDStatus(zeros), JobInfo(known JobID, 4).
2. ReleaseUnit with the JobID returned by **this live session**. Before START,
   only Release is needed. If all requested sheets were already confirmed,
   finish with normal END=6 and Release instead of ABORT.
3. Fresh Extended/Input status, bounded wait for Aux.PAPER_DELIVERY to clear.

Only acknowledged cleanup and settled delivery produce `cancelled`. A partial
write, missing/malformed reply, hard thread interrupt, rejected cleanup or
delivery timeout produces uncertainty and pauses successors. No cleanup is
appended to an exchange whose alignment is unknown. No automatic print replay.
An already accepted sheet may still exit after Cancel.

`CaptPrinterStateStore` atomically records reservation/END/ABORT/Release evidence
before further commands. Stored JobIDs are evidence only: they are **never**
reused after reopen/reconnect. Uncertain/open lifecycle or corrupt state blocks
new print sessions until explicit clear. An Activity teardown uses soft cancel
and orderly executor shutdown instead of interrupting the print worker.

## Clear queue and printer

The confirmation stops new intake, invalidates in-flight system imports,
signals active print cancellation and serializes maintenance behind the active
document job. Terminal records, private sources/prepared pages and per-job
reports are removed. Android PrintService handles are cancelled even when
their local row has already been removed. New system jobs submitted after
the clear request remain separate user intents.

Printer clearing is verified independently of deleting local records:

- Acquire the existing exclusive USB lease for one granted LBP2900. Allow a
  cancelled test worker to close first; never force-claim the interface.
- Read fresh Extended state; reject engine/other communication faults. Wait
  for physical delivery (Aux bit 4) before destructive clear commands.
- GoOffline if necessary and verify OFFLINE, then **DiscardData → ClearMisPrint
  → ClearError → GoOnline**, the order used by pinned `capt_reserve_unit`'s
  error recovery. Check each control ACK; poll bounded fresh Extended status
  for online idle and all five counters zero.
- Probe one **new** ReserveUnit; require zero Error/nonzero JobID; immediately
  Release that new ID. Read Input and fresh Extended status and verify again.
  The probe sends no JobInfo START, page descriptor, raster or StartPrint.

This is an explicit clear operation, never an automatic Reserve retry or
failed-job replay. It uses no USB reset, drain/flush or guessed stale JobID.
If unavailable/refused, the local list can be empty while the UI says printer
clear is unconfirmed and blocks new jobs. Reconnect/grant USB and press Clear
again. A pending maintenance marker survives process death. The private
maintenance result and reservation evidence are included in manual JSON export.

## Paper interruption and continuation

Recognize only the pinned error signature: Extended Eng=0, Cnt=40, Pap=00,
Bas contains NOTREADY+OFFLINE (12). Pap=00 alone, jam, cover, communication
faults or the short Basic 88 do not become a resumable paper-out diagnosis.

After sending page data, the safe continuation boundary additionally requires
Shipped=Printed=Printing equal to the last durable confirmed prefix,
Decoding/Received within that prefix or the next sheet, and no Aux delivery.
This means the rejected current sheet was not accepted by the engine. Cleanup
must be acknowledged as above. Otherwise keep an unknown outcome, without a
Continue button. Do not infer the last physical sheet solely from a successful
USB write or reset counters after GoOnline.

The queue retains source and CRC-protected prepared pages privately under the
existing 256 MiB quota, enters WAITING_PAPER and blocks successors. Android's
job is blocked with guidance to the app queue. On process restart **only** this
explicitly paused, released segment and a valid manifest may be retained; it
does not run automatically. Old queued/in-flight attempts still become
interrupted terminal outcomes and their source files are deleted.

The user loads paper and presses **Continue from sheet N**. The worker validates
all spool CRCs before USB, performs explicit clear, checks Input's reported
ready bit (payload byte 3 bit 40, zero Error) and fresh Extended Pap=80/idle,
then starts a new CAPT segment containing `outputPageOrder.drop(confirmed)`.
Global confirmation is `confirmedBeforeAttempt + segmentConfirmed`; local
StartPrint numbering restarts at 1 after reset. Selected ranges and collated
copies keep their original physical output order. No confirmed prefix is resent.

These fields report engine/input readiness, **not proven physical tray
presence**. LBP3000 source notes about no tray sensor/physical Go button are
not established LBP2900 hardware facts. We do not ask the user to press a
nonexistent Go button. If readiness stays absent, retain the pause and send no
new raster. Further hardware captures may require a model-specific adjustment.

## Verification

- JVM injected transports exercise exact clear/known-ID cancel order, ACK
  rejection, Reserve 8C, buffer flow, paper signature and boundary, partial
  writes, cancellation during raster, delivery settling, range/copy resume
  with new local counters, monotonic queue state and process recovery rules.
- API-36 smoke exercises actual AtomicFile/JSON/FileProvider and CRC spool
  retention across force-stop; a missing printer keeps paper jobs paused,
  corrupt spool cannot start maintenance, and explicit clear removes local
  rows/files while reporting printer clear unconfirmed. Debug fixtures are
  explicitly synthetic and send no USB commands.
- Native HISCOA smoke and manual-only sanitized JSON sharing guard remain.

CI cannot prove that ABORT=4 stops this printer, the clear probe releases its
stale state, the paper signature matches its firmware, or readiness changes
after loading paper. After a separately approved signed release, hardware
acceptance should cover Cancel during raster/confirmation, a following fresh
one-sheet job without power cycle, Clear with USB attached/detached, then a
three-sheet document with one sheet loaded: load paper, Continue, verify exact
sheet order/count and export JSON. Start with this bounded test rather than an
eleven-page document. Do not claim those observations before the owner supplies
the physical result and matching trace.
