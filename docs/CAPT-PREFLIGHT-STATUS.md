# Build-133 CAPT preflight investigation

> Historical checkpoint, superseded by [LBP2900-STARTUP-CONTRACT.md](LBP2900-STARTUP-CONTRACT.md)
> after the build-144 hardware reports. The earlier requirement to preserve the
> Basic veto is replaced by the shared LBP2900 Extended/Input policy. PR 29's
> mandatory SetPowerDown Error-byte interpretation was unjustified and is
> corrected; required JobInfo/StartPrint/END/Release ACK checks remain.


The owner's HONOR Documents jobs reached the Android service and the shared
document queue. A one-page job and a later 11-page job were fully prepared,
then stopped at PREFLIGHT with `A0 E0 06 00 88 00`, before ReserveUnit and with
zero print-data bytes. USB permission and non-forced claim were available.
The owner recalls cycling power; the reports do not prove when that happened
or include a fresh paired extended-status snapshot for these attempts.

Do not conflate these failures with the historical ReserveUnit `0x8C` replies.
Historical diagnostics contain Basic `0x88`, Extended Bas=`0x01` / Bas1=`0x88`
and a later successful Stage B job. Those events are not an atomic snapshot or
proof that bypassing Basic `0x88` is correct. This PR preserves that stop.

## Source audit and bounded change

- [Pinned ValdikSS capt-status.c](https://github.com/ValdikSS/captdriver/blob/892f320c019974a5b737e000fcc2c8582264a131/src/capt-status.c)
  reads basic status, conditionally refreshes extended status and reads input
  status on NEED_INPUT_STATUS.
- [Pinned LBP2900/3000 prologue](https://github.com/ValdikSS/captdriver/blob/892f320c019974a5b737e000fcc2c8582264a131/src/prn_lbp2900.c)
  settles input-status changes before reservation. Its automatic destructive
  clear/retry path is not adopted here.
- [Pinned flags](https://github.com/ValdikSS/captdriver/blob/892f320c019974a5b737e000fcc2c8582264a131/src/capt-status.h)
  name basic bits 0x08/0x80 IM_DATA_BUSY/ERROR. The older
  [agalakhov flags](https://github.com/agalakhov/captdriver/blob/175f8ff4464591feb67728c7752ac752c7b48d43/src/capt-status.h)
  use different names; none proves this printer's persistent short reply safe.

The document runner now takes paired Basic/Extended samples, consumes requested
InputStatus, then re-reads both replies before reservation. Transient busy,
pending input, delivery and outstanding-page states get bounded polling: up to
21 samples with a 5-second polling budget and 250 ms pauses. Bounded transport
latency is additional; this is not a five-second total USB deadline. Cancellation
is checked before every query and between samples. Hardware/error/paper flags
in ExtendedStatus and malformed replies stop immediately. Idle/offline-only
startup remains eligible for the existing job prologue once Basic also clears.

A persistent blocked Basic reply with otherwise idle Extended status becomes
`CAPT_STATUS_CONFLICT`, not a claimed physical fault or print success. Persistent
consistent busy/pending status remains `PRINTER_STATUS`; incomplete/malformed
frames become `CAPT_STATUS_INVALID`. Raw frames (up to 64 bytes per query) stay
in bounded per-job JSON trace; they contain service data, not document content.
The last-frame field now legitimately holds the most recent Extended/Input
reply, so inspect the trace for the paired Basic response.

No new state-changing command, reservation retry, reset, service command or
automatic replay is added. No signing, permission, raster or Stage B sequence
change is made. Existing source/queue quotas and manual sharing remain active.
Repeated Basic `0x88` can still block all document printing; no root-cause repair
is claimed without new hardware evidence.

## Verification and next hardware check

Pure status and full-runner tests cover captured persistent conflict, transient
clearance, input flags in either reply, input that never clears, paper/hardware
faults, outstanding pages/delivery, malformed/missing counters, cancellation,
clock/sample limits and one-reservation/no-replay execution. Synthetic mutations
model errors; they are not claimed physical traces. PR CI must build, lint,
run all JVM tests and the existing API-36 raster/system smoke tests.

After successful CI, review and a separately requested signed build:

1. Update the signed APK in place and grant access to the connected LBP2900.
2. Submit a **new one-page, one-copy A4** job from HONOR Documents once. Leave
   power unchanged during this attempt so the paired samples describe one run.
3. Send that new job's JSON and system-print JSON; state whether a sheet came
   out. A persistent conflict should contain repeated paired replies and zero
   print bytes, with no reservation. Stop after one failed/uncertain attempt.

Do not ask for further 11-page attempts while preflight is unresolved. Physical
Stage D acceptance, exact margins, second-source behavior and ReserveUnit
recovery remain open.

## Investigation follow-up (2026-10-23)

The short Basic response has transport-valid framing, but its phase-specific
status semantics are unverified. It is no longer presented as a proven physical
error. The bounded preflight gate is retained pending hardware evidence.
See [CAPT-PROTOCOL-FOLLOWUP.md](CAPT-PROTOCOL-FOLLOWUP.md) for ACK validation,
UNKNOWN/input resampling, bounded per-session wire evidence and the remaining
hardware decision. This is not a 0x88 bypass or a demonstrated printing repair.
