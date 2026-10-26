package com.cryonum.captusbprint.office;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Observed process/workspace limits. Not an OS sandbox or an allocation reservation. */
public final class OfficeWorkBudget {
    public enum Limit { TIME, MEMORY, DISK, NO_SPACE, FILE_COUNT, UNSAFE_PATH, OBSERVATION }
    public static final class Exceeded extends IOException {
        private static final long serialVersionUID = 1L;
        public final Limit limit;
        public Exceeded(Limit limit) { super(limit.name()); this.limit = limit; }
    }
    public final long timeoutMillis;
    public final long maxRssBytes;
    public final long maxDiskBytes;
    public final long reserveBytes;
    public final int maxFiles;

    public OfficeWorkBudget(long timeoutMillis, long maxRssBytes, long maxDiskBytes,
                            long reserveBytes, int maxFiles) {
        if (timeoutMillis <= 0 || maxRssBytes <= 0 || maxDiskBytes <= 0 ||
                reserveBytes < 0 || maxFiles <= 0) throw new IllegalArgumentException();
        this.timeoutMillis = timeoutMillis;
        this.maxRssBytes = maxRssBytes;
        this.maxDiskBytes = maxDiskBytes;
        this.reserveBytes = reserveBytes;
        this.maxFiles = maxFiles;
    }

    public void checkTime(long elapsedMillis) throws Exceeded {
        if (elapsedMillis < 0 || elapsedMillis >= timeoutMillis) throw new Exceeded(Limit.TIME);
    }

    public void checkRss(long rssBytes) throws Exceeded {
        if (rssBytes <= 0) throw new Exceeded(Limit.OBSERVATION);
        if (rssBytes > maxRssBytes) throw new Exceeded(Limit.MEMORY);
    }

    /** Never follow links or materialize a whole directory tree in memory. */
    public long checkDisk(File root, long freeBytes) throws IOException {
        return checkDisk(new File[] { root }, freeBytes, () -> false);
    }

    /** One shared counter across private source, workspace, preview and queue roots.
     * Missing roots may be created later; links/special files never become traversable. */
    public long checkDisk(File[] roots, long freeBytes, BooleanSupplier cancelled) throws IOException {
        if (freeBytes < reserveBytes) throw new Exceeded(Limit.NO_SPACE);
        long[] bytes = {0};
        int[] entries = {0};
        for (File root : roots) Files.walkFileTree(root.toPath(), new SimpleFileVisitor<Path>() {
            private void count() throws Exceeded {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new CancellationException();
                if (++entries[0] > maxFiles) throw new Exceeded(Limit.FILE_COUNT);
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                count(); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                count();
                if (!attrs.isRegularFile()) throw new Exceeded(Limit.UNSAFE_PATH);
                long size = attrs.size();
                if (size < 0 || size > maxDiskBytes - bytes[0]) throw new Exceeded(Limit.DISK);
                bytes[0] += size;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw new Exceeded(Limit.OBSERVATION);
            }
        });
        return bytes[0];
    }

    /** Only VmRSS is accepted. VmSize/PSS/Java heap are not substitutes for resident memory. */
    public static long parseRss(String status) throws Exceeded {
        for (String line : status.split("\n")) {
            if (!line.startsWith("VmRSS:")) continue;
            String[] fields = line.substring(6).trim().split("\\s+");
            try {
                if (fields.length != 2 || !fields[1].equals("kB")) break;
                long kb = Long.parseLong(fields[0]);
                if (kb <= 0 || kb > Long.MAX_VALUE / 1024) break;
                return kb * 1024;
            } catch (NumberFormatException invalid) { break; }
        }
        throw new Exceeded(Limit.OBSERVATION);
    }

    private OfficeWorkBudget() { throw new AssertionError(); }
}
