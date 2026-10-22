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

## Owner validation and next offline checkpoint
- Owner confirmed on 2026-10-22 that signed build 74 opens a real PDF and reports its page count, file bytes and dimensions of its first page through PdfRenderer. This confirms local intake/metadata on the actual HONOR handset, **not** PDF printing.
- This change reads dimensions of every PDF page sequentially without rendering a full bitmap, displays up to 20 entries, and offers an **offline-only** A4 page-selection/copy/orientation plan. Empty page selection means all pages; invalid, duplicate and overlapping ranges are rejected. Output is bounded to 2,000 planned sheets and the first 24 page indices are shown. No job is queued, no document contents retained, and no CAPT/USB document printing is activated.
- Next work remains bounded ownership of a selected local source, a single USB queue, preview and deterministic sequential rasterization. The page plan cannot be treated as printed sheets.

## Explicitly NOT completed by this foundation
A visual page preview, actual rasterization, geometry and monochrome conversion,
PDF/image CAPT printing, physical use of the offline page/copy/orientation plan,
cancellation, source access retention for queued jobs, single shared USB
queue, system PrintService and real-document acceptance remain to be built.
The importer performs *offline inspection only*.

## Stage C implementation sequence
1. Safe local import/format validation and document metadata (owner-confirmed on real PDF; per-page sizes enhanced here).
2. Immutable JobSpecification and normalized page range/copy plan (offline UI done here); bounded document ownership and one queue remain pending.
3. Actual 600dpi A4 preview-to-print geometry and sequential page rasterizer;
   inspect text, formulas, thin lines, EXIF orientation and greyscale.
4. Reuse Stage B CAPT engine for document pages without exposing a separate
   USB claimant. Implement cancellation/uncertain-result states.
5. Owner prints PDF, JPEG and PNG and checks page order, margins,
   offline use, interruption and recovery before accepting Stage C.

Do not interpret the green app build as validation of physical document printing.
