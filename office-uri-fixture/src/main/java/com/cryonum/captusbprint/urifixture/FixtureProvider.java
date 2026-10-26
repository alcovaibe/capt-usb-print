package com.cryonum.captusbprint.urifixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Deliberately adversarial, synthetic-only helper APK. Never package in CAPT or publish. */
public final class FixtureProvider extends ContentProvider {
    private final List<ParcelFileDescriptor> held = new ArrayList<>();
    private volatile int opens;
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle reply = new Bundle();
        // This test control endpoint intentionally grants only to a fixed debug package.
        if ("grant".equals(method)) getContext().grantUriPermission("com.cryonum.captusbprint.debug",
                Uri.parse(arg), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        else if ("revoke".equals(method)) getContext().revokeUriPermission(Uri.parse(arg), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        else if ("reset".equals(method)) synchronized (held) {
            for (ParcelFileDescriptor fd : held) try { fd.close(); } catch (Exception ignored) { }
            held.clear();
        }
        else if ("process".equals(method)) {
            int pid = Integer.parseInt(arg);
            if (pid <= 1) throw new IllegalArgumentException();
            boolean gone = false;
            try { android.system.Os.kill(pid, 0); }
            catch (android.system.ErrnoException error) { gone = error.errno == android.system.OsConstants.ESRCH; }
            reply.putBoolean("gone", gone); // EPERM still means the other UID's PID exists.
        }
        reply.putInt("opens", opens);
        reply.putInt("uid", android.os.Process.myUid());
        return reply;
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return openAssetFile(uri, mode, null);
    }
    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        opens++;
        String name = uri.getLastPathSegment();
        try {
            if ("open-block".equals(name)) {
                // Ignore CancellationSignal and interrupts, as an uncooperative remote provider can.
                long end = android.os.SystemClock.elapsedRealtime() + 120000;
                while (android.os.SystemClock.elapsedRealtime() < end) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                }
                throw new FileNotFoundException();
            }
            if ("failure".equals(name)) throw new FileNotFoundException("CAPT_URI_PRIVATE_DETAIL_57C3");
            if ("slice".equals(name)) {
                File file = new File(getContext().getCacheDir(), "slice");
                long length;
                try (OutputStream out = new java.io.FileOutputStream(file);
                     InputStream input = getContext().getAssets().open("office-fixtures/word.docx")) {
                    out.write(new byte[37]); length = copy(input, out); out.write(new byte[53]);
                }
                return new AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 37, length);
            }
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            if ("read-block".equals(name)) {
                synchronized (held) { held.add(pipe[1]); }
            } else new Thread(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if ("standard.pdf".equals(name)) {
                        android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
                        android.graphics.pdf.PdfDocument.Page page = pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(595,842,1).create());
                        page.getCanvas().drawText("Synthetic PDF",40,60,new android.graphics.Paint());
                        pdf.finishPage(page); pdf.writeTo(out); pdf.close();
                    } else if ("standard.png".equals(name) || "standard.jpg".equals(name)) {
                        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(160,120,android.graphics.Bitmap.Config.ARGB_8888);
                        image.eraseColor(android.graphics.Color.BLUE);
                        image.compress("standard.png".equals(name) ? android.graphics.Bitmap.CompressFormat.PNG : android.graphics.Bitmap.CompressFormat.JPEG,90,out);
                        image.recycle();
                    } else if ("oversized".equals(name)) {
                        byte[] block = new byte[65536];
                        for (int i=0; i<1040; ++i) out.write(block);
                    } else if ("corrupt".equals(name)) out.write(new byte[]{1,2,3,4,5,6,7,8});
                    else if (!"empty".equals(name)) {
                        String asset = name != null && name.matches("(word\\.(docx|doc)|workbook\\.(xlsx|xls))") ? name : "word.docx";
                        try (InputStream in = getContext().getAssets().open("office-fixtures/" + asset)) { copy(in, out); }
                    }
                } catch (Exception ignored) { /* Reader cancellation/broken pipe is expected. */ }
            }, "synthetic-uri-writer").start();
            return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (FileNotFoundException error) { throw error; }
        catch (Exception error) { throw new FileNotFoundException("SYNTHETIC_PROVIDER_FAILED"); }
    }
    private static long copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buffer = new byte[65536]; long total = 0; int n;
        while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); total += n; }
        return total;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new AssertionError("Metadata must not be queried"); }
    @Override public String getType(Uri uri) { return "image/png"; } // Deliberately misleading.
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
