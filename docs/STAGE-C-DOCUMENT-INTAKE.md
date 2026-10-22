# Stage C — document intake foundation (no real-document printing yet)

Stage B was owner-accepted on 2026-10-22 after build 71 printed two distinct
A4 pages in one reserved CAPT job. This change begins Stage C without
altering the hardware-confirmed Stage B USB engines.

## Initial supported flow
- Main screen offers **«Выбрать PDF / JPEG / PNG (без печати)»**.
- Android ACTION_OPEN_DOCUMENT selects a user-granted content URI. The same
  inspector is exposed as an ACTION_SEND receiver for supported MIME types.
- A worker thread copies a bounded source (maximum 64 MiB) into an ephemeral
  private cache file, including for providers without seekable descriptors.
  Source MIME/filename cannot override checked PDF/JPEG/PNG magic bytes.
- PDF metadata is read using Android PdfRenderer (requires seekable source).
  Password-protected or unsupported PDFs return a visible error.
- Image dimensions are probed without allocating a full-color bitmap.
  The platform ExifInterface reads image rotation for review. Rotations not
  yet handled by the limited metadata view are not silently normalized.
- Invalid, empty, inaccessible, oversized or malformed documents are rejected.
  Temporary files are deleted on success or failure.
- No document contents, names or URIs enter exported diagnostic history.
  No document print commands, Internet permission or automatic sharing are added.

## Owner-confirmed on-device intake and next offline plan

- On signed build 74, the owner confirmed that the HONOR X9c opened a PDF and displayed
  its page count and document information. This verifies the local inspector for that
  PDF only; JPEG, PNG, provider failures and actual rendering remain unverified on hardware.
- DocumentActivity now shows optional page ranges (empty means all; e.g. `1–3, 5, 8`),
  1–20 copies and A4 portrait/landscape after successful inspection. An explicit
  **«Проверить план задания (без печати)»** validates and displays a bounded collated
  output order using the existing immutable JobSpecification. Overlaps, missing pages,
  malformed ranges and more than 2000 output sheets are rejected.
- The metadata-only inspector still closes temporary sources. The preview session
  retains one validated source only while the document screen is open. The page/copy
  plan remains a pure calculation and does not enqueue or print that source; no
  USB, CAPT, Telegram or PrintService commands are issued.
- Dedicated unit tests cover page selection, page order, copies, orientation,
  limits and malformed input.

## Explicitly NOT completed by this foundation
The display-only visual preview needs device verification; actual print rasterization, geometry and monochrome conversion,
PDF/image CAPT printing, applying the offline options to actual print jobs,
cancellation, source access retention for queued jobs, single shared USB
queue, system PrintService and real-document acceptance remain to be built.
The importer and display preview perform *offline actions only*.


## Offline visual preview (new implementation; owner hardware test pending)

- After a validated PDF/PNG/JPEG intake, the document screen displays a locally rendered page on a neutral paper-style surface. PDF supports previous/next page navigation; images appear as one page. No print button is added by this change.
- Retain exactly one validated private file (up to 64 MiB) while the preview screen owns it. Release it when another document replaces it or the screen is destroyed. Do not log source names, content URIs, or page raster data.
- Render only the requested PDF page using Android PdfRenderer, or sample a PNG/JPEG with BitmapFactory. Bound the displayed bitmap to a 1600-pixel edge and 2.4M pixels; apply EXIF orientation (including mirrored cases) to the sampled image. Work runs on one background executor and stale rendered bitmaps are recycled.
- The visual preview is display-only. It does **not yet simulate the real 600-dpi print geometry**, apply copies/page ranges to actual output, generate HISCOA document frames, claim USB, or trigger Telegram. Those are subsequent Stage C milestones.
- Owner acceptance still requires checking an actual PDF with several pages, one JPEG with EXIF rotation, one PNG, page navigation and repeated file changes on HONOR X9c. A green CI alone is insufficient.

## Stage C implementation sequence
1. Safe local import/format validation and document metadata (this PR).
2. Immutable JobSpecification and normalized page range/copy plan: offline UI and
   validation implemented; retained document ownership and the one shared queue pending.
3. Actual 600dpi A4 preview-to-print geometry and sequential page rasterizer;
   inspect text, formulas, thin lines, EXIF orientation and greyscale.
4. Reuse Stage B CAPT engine for document pages without exposing a separate
   USB claimant. Implement cancellation/uncertain-result states.
5. Owner prints PDF, JPEG and PNG and checks page order, margins,
   offline use, interruption and recovery before accepting Stage C.

Do not interpret the green app build as validation of physical document printing.


## Stage C shared A4 layout proposal (feature branch, not yet merged or hardware-accepted)

- Pure `A4PageGeometry.fit` derives layout from the Stage B physically accepted
  4960 × 7016 portrait A4 / 600-dpi grid. Landscape is a logical 7016 × 4960
  sheet; mapping/rotation onto the portrait CAPT wire grid is NOT implemented.
- The current FIT-only layout uses a symmetric provisional 260-pixel software
  inset (about 11 mm at 600 dpi). This is a conservative layout choice informed
  by Stage B registration marks, NOT a measured hardware printable boundary.
  No 100% physical-size or margin-accuracy claim is made.
- The display bitmap for each PDF page or EXIF-oriented JPEG/PNG is now composed
  on a white A4 sheet from that same placement, scaled to bounded display size.
  Portrait/landscape changes re-render the current page. The preview is still
  display-quality, not a 600-dpi monochrome or CAPT print raster.
- Pure JVM geometry tests check both logical orientations, bounds, aspect ratio,
  preview-to-physical placement mapping, pixel budget and rejected invalid input.
- Next: verify the new layout on HONOR (portrait/landscape PDF, EXIF JPEG, PNG);
  implement bounded sequential print rasterization, rotation for landscape CAPT,
  mono conversion and owner-measured printed scale BEFORE enabling document print.
- Neither Stage B engines nor USB ownership, signed release, sharing or PrintService
  are changed by this proposal.

## Stage C: Android 16 insets, explicit content rotation, offline PDF bands (PR pending)

- The owner inspected signed build 89 and confirmed A4 PDF preview. A portrait PDF
  on landscape paper is intentionally smaller under FIT; this is not a printer fault.
  Screenshot exposed scroll content moving underneath status/navigation bars.
- The ScrollView **viewport** (not its inner scrolling content) now receives
  system-bar, display-cutout and IME insets; clipToPadding protects scrolled text.
- Orientation defaults to Auto, resolved from the FIRST document page (not device
  rotation); portrait/landscape are explicit overrides. Auto does not promise
  per-page mixed-orientation printing. A separate optional clockwise 90-degree
  **content** rotation is retained in immutable JobSpecification and reused
  by both PDF and EXIF-adjusted image previews. A portrait source on landscape
  paper without content rotation still remains smaller under FIT.
- Initial offline PDF PRINT-mode preparation streams one page in 64-line ARGB
  strips at 4960 × 7016 A4/600 dpi. Each strip becomes MSB-first 1bpp with a
  deterministic absolute-coordinate 4×4 ordered grayscale dither. The full
  600-dpi ARGB page is never allocated; no full-page band list is retained.
  The user-triggered current-page verification only counts bounded raster bytes.
  It makes **no** USB, HISCOA, job, PrintService, Telegram or network call.
- This new band prototype is deliberately **portrait only**. Landscape wire
  rotation, JPEG/PNG full-resolution bands, validated HISCOA conversion, unified
  job queue, physical document printing and interruption recovery are still
  pending. Passing JVM and CI does NOT prove visual strip seams or printed DPI.


## Stage C owner check of signed build 93 (2026-10-22)

The owner checked the new signed build on HONOR X9c and explicitly confirmed all
seven requested outcomes: status bar no longer overlaps text; Android bottom
navigation does not obscure buttons; automatic A4 orientation selects portrait
for the tested PDF; landscape toggle works; 90-degree content rotation works;
PDF page navigation works; offline PDF current-page raster check finishes
without an error. This is an owner-reported UI/offline acceptance, not a
measured physical PDF print or evidence for JPEG/PNG print quality. Exact
raster counts were not provided in that report.

## Next offline-only milestone: PDF mono bands into HISCOA

- A manually invoked, portrait-only one-page pipeline reuses the Stage B
  bundled native HISCOA codec, giving it exactly one 64-line (final 40-line)
  MSB-first 1bpp band at a time. Last-band marker is set only on the final band.
- Each incoming band must be sequential, correctly sized and within the
  hardware-tested A4/600dpi grid. Every native output must be nonempty and at
  most twice its input size plus 64 bytes; additionally cap total compressed
  output at 12 MiB. Cancel before/between bands without any automatic retry.
- Only the current raw and compressed bands are transiently held; the resulting
  summary exposes band count, total raster bytes, total compressed bytes and
  maximum compressed band length. Neither document contents nor a document hash,
  source name or URI enter the diagnostic report.
- **No CAPT framing, USB transfer, native encoder modifications, document
  printing, queue submission, PrintService or new permission** is added.
  The owner must validate native compression on HONOR separately. CI/JVM
  tests use an injected fake codec and cannot prove native compression correctness.
- After this checkpoint: validate a real PDF band preparation result, inspect
  print-mode raster content/strip seams and grayscale, then design one shared
  job-owner queue and a guarded, separately consented first physical PDF sheet.
