# CAPT investigation follow-up: acknowledgements and phase evidence

Baseline: main 75e7c5176ca0c157a5ba5bd5d5d3b14a78b94123 (PR 28).
Reference: ValdikSS/captdriver 892f320c019974a5b737e000fcc2c8582264a131.

This change fixes source-proven acknowledgement and diagnostic collection defects.
It does **not** establish the meaning of the owner-captured short BasicStatus
`A0 E0 06 00 88 00`, or claim physical document printing is repaired.
The existing bounded pre-reservation stop remains. No reset, reservation retry,
endpoint flush, automatic replay, new Basic probes or speculative teardown is added.

## Corrected behavior

- Every print engine records control replies before validation. The Error byte of
  SetPowerDown, SetJobInfo2, GoOffline, GoOnline, DiscardData, ClearMisPrint,
  ClearError, StartPrint and ReleaseUnit must be zero. The opaque second byte is
  retained without assigning it a meaning. This contract does not apply to status
  queries or ReserveUnit, whose status/JobID parser remains separate.
- A negative END/Release reply cannot produce a successful released-job result.
  Previously confirmed pages remain a lower bound, and the outcome remains
  uncertain without automatic repetition. USB close is not CAPT release.
- Basic replies expose payload length and response form. A two-byte payload is
  `SHORT_UNVERIFIED`; its bit names are candidate interpretations, not a physical
  error diagnosis. Diagnostic assessment remains UNKNOWN for that form.
  Full described layout means layout only, not new hardware acceptance.
- Diagnostics decide whether to sample again from input/busy/counter/conflict
  observations, separately from UNKNOWN classification. Captured secondary flags
  0x88/0x8A no longer suppress bounded repeats or post-Input status pairs.
- Print JSON includes `protocolDiagnostics`: a random session ID, build, owner,
  PID/thread, monotonic event time, phase, claim/endpoints/packet sizes, command IDs,
  requested/actual USB counts, full bounded control IN frames, partial frames on
  read failure and confirmed reservation/page/end/release state.
- The log keeps its first 24 and last 72 events, with an explicit omitted count.
  It never copies OUT payloads, document/raster bytes, names, URIs, serial numbers,
  USB paths or JobInfo strings. It is private and exported only by existing
  explicit manual actions; raw events are not passed to UI progress or Logcat.
- The shared printing reader preserves the existing six-byte prefix then declared
  remainder. It handles partial reads, prefers LE length (56, not BCD 38), and
  stops on wrong opcode or incomplete input. It never searches for another frame
  after an error. Identical-opcode stale replies remain unproven, not excluded.

## Evidence and test scope

Existing hardware evidence: two build-133 SYSTEM_PDF jobs stopped before Reserve
with zero raster bytes and short Basic 0x88. Earlier test jobs succeeded; old
single-page PDF success is owner-reported. Separate Reserve 0x8C failures are not
the same event. No raw owner report is committed in this PR.

Regression tests use captured frames for raw layout and UNKNOWN/resampling, and
explicit synthetic mutations for command rejection, cancellation, partial reads,
wrong/late opcode and deadlines. Engine tests return correctly framed negative
ACKs at initialization, END and Release; an IOException-only test is insufficient.
Synthetic successful Basic replies do not constitute positive hardware evidence.
Trace tests check bounds, setup/failure retention, lifecycle and OUT redaction.

## Next hardware evidence

After an owner-reviewed merge and the normal signed build, keep printer power
unchanged and collect **one** new one-sheet/copy job through HONOR Documents,
then export the existing footer JSON manually and report whether a sheet emerged.
On persistent preflight refusal, the paired trace should show Basic/Extended/Input,
zero raster and no Reserve; do not repeat an eleven-sheet job.

Separately, an explicitly started accepted one-sheet test/PDF path now passively
records its existing command sequence and all control replies. It does not add
Basic queries. Comparing those sessions can establish ACK/lifecycle behavior;
to prove Basic phase semantics, a later controlled single-variable experiment
(or printer-side USB capture) is still required. Do not infer it from fake
ready responses, remove the gate, or insert state-changing recovery based on this PR.

Once the phase-specific contract is known, implement one model-specific status
policy rather than a 0x88 whitelist. Buffer drain before BlackEnd and InputStatus
during printing are separate downstream investigations, not explanations of the
already observed zero-data preflight failures.
