package com.cryonum.captusbprint.document;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Bounded private source copy only. This class does not parse or render Office files. */
public final class PrivateDocumentImport {
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    public static final long HEADROOM_BYTES = 8L * 1024 * 1024;
    private static final long TIMEOUT_NANOS = 30_000_000_000L;

    private PrivateDocumentImport() {}

    public enum Reason { TOO_LARGE, NO_SPACE, EMPTY, NO_DATA, TIMED_OUT, UNSUPPORTED }

    /** Only a fixed code may leave this layer; provider exception messages can contain private data. */
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final Reason reason;
        public Failure(Reason reason) {
            super("DOCUMENT_" + reason.name());
            this.reason = reason;
        }
    }

    @FunctionalInterface
    public interface Source { InputStream open() throws IOException; }

    public static File copy(File directory, Source source, BooleanSupplier cancelled,
                            LongSupplier availableBytes) throws IOException {
        return copy(directory, source, cancelled, availableBytes, MAX_BYTES,
                HEADROOM_BYTES, TIMEOUT_NANOS, System::nanoTime);
    }

    // Injectable budgets/clock permit resource and cancellation tests without large allocations.
    static File copy(File directory, Source source, BooleanSupplier cancelled,
                     LongSupplier availableBytes, long maxBytes, long headroom,
                     long timeoutNanos, LongSupplier clock) throws IOException {
        if (maxBytes <= 0 || headroom < 0 || timeoutNanos <= 0) {
            throw new IllegalArgumentException("Invalid import budget");
        }
        long started = clock.getAsLong();
        check(cancelled, started, timeoutNanos, clock);
        if (availableBytes.getAsLong() < headroom) throw new Failure(Reason.NO_SPACE);
        File target = File.createTempFile("incoming-", ".bin", directory);
        boolean complete = false;
        try {
            try (InputStream input = source.open()) {
                if (input == null) throw new Failure(Reason.NO_DATA);
                check(cancelled, started, timeoutNanos, clock);
                try (OutputStream output = Files.newOutputStream(target.toPath())) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    while (true) {
                        check(cancelled, started, timeoutNanos, clock);
                        int count = input.read(buffer);
                        check(cancelled, started, timeoutNanos, clock);
                        if (count < 0) break;
                        if (count == 0) throw new Failure(Reason.NO_DATA);
                        if (count > maxBytes - total) throw new Failure(Reason.TOO_LARGE);
                        long available = availableBytes.getAsLong();
                        if (available < headroom || available - headroom < count) {
                            throw new Failure(Reason.NO_SPACE);
                        }
                        output.write(buffer, 0, count);
                        total += count;
                    }
                    if (total == 0) throw new Failure(Reason.EMPTY);
                }
            }
            // Cancellation/close failures must not publish a source.
            check(cancelled, started, timeoutNanos, clock);
            complete = true;
            return target;
        } finally {
            if (!complete) Files.deleteIfExists(target.toPath());
        }
    }

    private static void check(BooleanSupplier cancelled, long started, long timeout,
                              LongSupplier clock) throws Failure {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("DOCUMENT_IMPORT_CANCELLED");
        }
        if (clock.getAsLong() - started >= timeout) throw new Failure(Reason.TIMED_OUT);
    }
}
