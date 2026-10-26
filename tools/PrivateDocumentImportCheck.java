package com.cryonum.captusbprint.document;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Same production importer on the host JVM; no Android, USB or Office engine simulation. */
public final class PrivateDocumentImportCheck {
    private static int checks;
    private static void expect(Class<? extends Throwable> type, Throwing action) throws Exception {
        try { action.run(); }
        catch (Throwable e) {
            if (!type.isInstance(e)) throw new AssertionError("Unexpected failure", e);
            checks++;
            return;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError();
        checks++;
    }
    private interface Throwing { void run() throws Exception; }
    private static void fail(PrivateDocumentImport.Reason reason, Throwing action) throws Exception {
        try { action.run(); }
        catch (PrivateDocumentImport.Failure e) { require(e.reason == reason); return; }
        throw new AssertionError("Expected " + reason);
    }
    private static File copy(File dir, PrivateDocumentImport.Source source,
                             java.util.function.BooleanSupplier cancelled,
                             java.util.function.LongSupplier space, long limit) throws IOException {
        return PrivateDocumentImport.copy(dir, source, cancelled, space, limit, 8, 100, () -> 0);
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("capt-import-test-").toFile();
        try {
            byte[] content = new byte[64 * 1024 + 37];
            Arrays.fill(content, (byte) 123);
            AtomicBoolean closed = new AtomicBoolean();
            File copied = copy(dir, () -> new ByteArrayInputStream(content) {
                @Override public void close() { closed.set(true); }
            }, () -> false, () -> Long.MAX_VALUE, content.length);
            require(Arrays.equals(content, Files.readAllBytes(copied.toPath())));
            require(closed.get());
            Files.delete(copied.toPath());
            fail(PrivateDocumentImport.Reason.TOO_LARGE, () -> copy(dir,
                    () -> new ByteArrayInputStream(content), () -> false,
                    () -> Long.MAX_VALUE, content.length - 1));
            require(dir.list().length == 0);
            fail(PrivateDocumentImport.Reason.EMPTY, () -> copy(dir,
                    () -> new ByteArrayInputStream(new byte[0]), () -> false, () -> 100, 100));
            fail(PrivateDocumentImport.Reason.NO_DATA, () -> copy(dir,
                    () -> null, () -> false, () -> 100, 100));
            fail(PrivateDocumentImport.Reason.NO_DATA, () -> copy(dir,
                    () -> new InputStream() {
                        @Override public int read() { return 0; }
                        @Override public int read(byte[] b) { return 0; }
                    }, () -> false, () -> 100, 100));
            AtomicBoolean opened = new AtomicBoolean();
            fail(PrivateDocumentImport.Reason.NO_SPACE, () -> copy(dir, () -> {
                opened.set(true); return new ByteArrayInputStream(content);
            }, () -> false, () -> 7, content.length));
            require(!opened.get());
            AtomicLong space = new AtomicLong(Long.MAX_VALUE);
            fail(PrivateDocumentImport.Reason.NO_SPACE, () -> copy(dir,
                    () -> new ByteArrayInputStream(content) {
                        @Override public synchronized int read(byte[] b, int off, int len) {
                            int n = super.read(b, off, len);
                            if (pos > 64 * 1024) space.set(8);
                            return n;
                        }
                    }, () -> false, space::get, content.length));
            require(dir.list().length == 0);
            expect(CancellationException.class, () -> copy(dir, () -> {
                throw new AssertionError("Cancelled import must not open provider");
            }, () -> true, () -> 100, 100));
            AtomicBoolean cancelled = new AtomicBoolean();
            closed.set(false);
            expect(CancellationException.class, () -> copy(dir,
                    () -> new ByteArrayInputStream(content) {
                        @Override public synchronized int read(byte[] b, int off, int len) {
                            cancelled.set(true); return super.read(b, off, len);
                        }
                        @Override public void close() { closed.set(true); }
                    }, cancelled::get, () -> Long.MAX_VALUE, content.length));
            require(closed.get() && dir.list().length == 0);
            AtomicLong clock = new AtomicLong();
            fail(PrivateDocumentImport.Reason.TIMED_OUT, () -> PrivateDocumentImport.copy(dir,
                    () -> new ByteArrayInputStream(content) {
                        @Override public synchronized int read(byte[] b, int off, int len) {
                            clock.set(100); return super.read(b, off, len);
                        }
                    }, () -> false, () -> Long.MAX_VALUE, content.length, 8, 100, clock::get));
            expect(IOException.class, () -> copy(dir, () -> {
                throw new IOException("Synthetic provider error");
            }, () -> false, () -> 100, 100));
            expect(IOException.class, () -> copy(dir,
                    () -> new ByteArrayInputStream(new byte[] {1}) {
                        @Override public void close() throws IOException { throw new IOException(); }
                    }, () -> false, () -> 100, 100));
            expect(AssertionError.class, () -> copy(dir, () -> {
                throw new AssertionError("Synthetic failure");
            }, () -> false, () -> 100, 100));
            Thread.currentThread().interrupt();
            try {
                expect(CancellationException.class, () -> copy(dir,
                        () -> new ByteArrayInputStream(content), () -> false, () -> 100, 100));
            } finally { Thread.interrupted(); }
            require(dir.list().length == 0);
            System.out.println("CAPT_PRIVATE_IMPORT_PASSED checks=" + checks);
        } finally {
            for (File file : dir.listFiles()) Files.delete(file.toPath());
            Files.delete(dir.toPath());
        }
    }
}
