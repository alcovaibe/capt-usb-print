package com.cryonum.captusbprint.office;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Serial ownership of one prepared document. This is NOT a print queue.
 * Options must be immutable; delivery must serialize callbacks (Android main looper).
 * Resources stay on the worker, so delayed UI callbacks never own source/PDF files.
 */
public final class LatestPreparation<O, T extends Closeable, M, F extends Enum<F>> implements Closeable {
    public enum Phase { LOADING, READY, CANCELLED, FAILED }
    public static final class State<M, F> {
        public final long version;
        public final Phase phase;
        public final M metadata;
        public final F failure;
        State(long version, Phase phase, M metadata, F failure) {
            this.version = version; this.phase = phase; this.metadata = metadata; this.failure = failure;
        }
    }
    @FunctionalInterface public interface Prepare<O, T> {
        T run(O options, BooleanSupplier cancelled) throws Exception;
    }
    @FunctionalInterface public interface Read<T, R> {
        R run(T document, BooleanSupplier cancelled) throws Exception;
    }
    public static final class CleanupFailure extends IOException {
        private static final long serialVersionUID = 1L;
        CleanupFailure() { super("PREPARATION_CLEANUP_FAILED"); }
    }
    private final ExecutorService worker;
    private final Executor delivery;
    private final Prepare<O, T> prepare;
    private final Function<T, M> describe;
    private final Function<Throwable, F> failure;
    private final Consumer<State<M, F>> listener;
    private final Runnable finalCleanup;
    private final AtomicLong version = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cleanupFailed = new AtomicBoolean();
    // Access only on the serial worker, including render/queue-copy reads and close.
    private T ready;
    private long readyVersion;

    public LatestPreparation(Executor delivery, Prepare<O, T> prepare, Function<T, M> describe,
                             Function<Throwable, F> failure, Consumer<State<M, F>> listener,
                             Runnable finalCleanup) {
        this(Executors.newSingleThreadExecutor(), delivery, prepare, describe, failure, listener, finalCleanup);
    }
    LatestPreparation(ExecutorService worker, Executor delivery, Prepare<O, T> prepare,
                      Function<T, M> describe, Function<Throwable, F> failure,
                      Consumer<State<M, F>> listener, Runnable finalCleanup) {
        this.worker = worker; this.delivery = delivery; this.prepare = prepare;
        this.describe = describe; this.failure = failure; this.listener = listener;
        this.finalCleanup = finalCleanup;
    }
    private boolean active(long token) { return !closed.get() && token == version.get(); }
    private void publish(State<M, F> state) {
        delivery.execute(() -> { if (active(state.version)) listener.accept(state); });
    }
    public synchronized long replace(O options) {
        if (closed.get()) throw new IllegalStateException("PREPARATION_CLOSED");
        long token = version.incrementAndGet();
        publish(new State<>(token, Phase.LOADING, null, null));
        worker.execute(() -> {
            if (!active(token)) return;
            T next = null;
            try {
                releaseReady();
                if (cleanupFailed.get()) throw new CleanupFailure();
                next = prepare.run(options, () -> !active(token));
                if (next == null) throw new IllegalStateException("PREPARATION_EMPTY_RESULT");
                M metadata = describe.apply(next);
                if (!active(token)) return;
                ready = next; next = null; readyVersion = token;
                publish(new State<>(token, Phase.READY, metadata, null));
            } catch (Throwable error) {
                if (active(token)) publish(new State<>(token, Phase.FAILED, null, failure.apply(error)));
            } finally { dispose(next); }
        });
        return token;
    }
    public synchronized void cancel() {
        if (closed.get()) return;
        long token = version.incrementAndGet();
        publish(new State<>(token, Phase.CANCELLED, null, null));
        worker.execute(this::releaseReady);
    }
    /**
     * Read/render/copy only the exact prepared version the user reviewed.
     * discard must be thread-safe: it can run on worker or delivery after a late callback.
     * Do not activate USB inside a read; a held common-queue job requires a current UI confirmation.
     */
    public synchronized <R> boolean read(long token, Read<T, R> read, Consumer<R> success,
                                         Consumer<F> failed, Consumer<R> discard) {
        if (!active(token)) return false;
        worker.execute(() -> {
            if (!active(token)) return;
            try {
                if (ready == null || readyVersion != token) throw new CancellationException();
                R result = read.run(ready, () -> !active(token));
                if (!active(token)) { discard.accept(result); return; }
                delivery.execute(() -> {
                    if (active(token)) success.accept(result); else discard.accept(result);
                });
            } catch (Throwable error) {
                F code = failure.apply(error);
                delivery.execute(() -> { if (active(token)) failed.accept(code); });
            }
        });
        return true;
    }
    public long version() { return version.get(); }
    public boolean isClosed() { return closed.get(); }
    public boolean cleanupFailed() { return cleanupFailed.get(); }
    private void dispose(T value) {
        if (value == null) return;
        try { value.close(); } catch (Throwable ignored) { cleanupFailed.set(true); }
    }
    private void releaseReady() {
        T old = ready; ready = null; readyVersion = 0;
        dispose(old);
    }
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        version.incrementAndGet();
        // No shutdownNow: source cleanup must run AFTER conversion/preview/queue-copy releases it.
        worker.execute(() -> {
            releaseReady();
            try { finalCleanup.run(); } catch (Throwable ignored) { cleanupFailed.set(true); }
        });
        worker.shutdown();
    }
}
