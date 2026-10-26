package com.cryonum.captusbprint.office;

/** Arithmetic only. Observations are not kernel RSS/filesystem quotas or reservations. */
public final class OfficeResourcePolicy {
    public static final long MIB = 1024L * 1024;
    public static final long SYSTEM_HEADROOM = 64 * MIB;
    public static final long APP_RSS_LIMIT = 1024 * MIB;
    public static final long OWNED_DISK_LIMIT = 512 * MIB;
    public static final long DISK_HEADROOM = 32 * MIB;
    public static final long CONVERSION_GROWTH = (192 + 64 + 64) * MIB;
    public static final long IMPORT_GROWTH = 64 * MIB + CONVERSION_GROWTH;

    public static void checkMemory(long total, long available, long threshold, boolean lowMemory,
                                   long mainRss, long workerRss, long requestedWorkerLimit)
            throws OfficeWorkBudget.Exceeded {
        if (total <= 0 || available < 0 || available > total || threshold < 0 || threshold > total ||
                mainRss <= 0 || workerRss < 0 || requestedWorkerLimit <= 0)
            throw new OfficeWorkBudget.Exceeded(OfficeWorkBudget.Limit.OBSERVATION);
        long workerLimit = Math.min(requestedWorkerLimit, total / 3);
        long appLimit = Math.min(APP_RSS_LIMIT, total / 2);
        if (lowMemory || available < threshold || available - threshold < SYSTEM_HEADROOM ||
                workerRss > workerLimit || mainRss > appLimit || workerRss > appLimit - mainRss)
            throw new OfficeWorkBudget.Exceeded(OfficeWorkBudget.Limit.MEMORY);
    }

    public static void checkSpace(long available, long growth) throws OfficeWorkBudget.Exceeded {
        if (available < 0 || growth < 0)
            throw new OfficeWorkBudget.Exceeded(OfficeWorkBudget.Limit.OBSERVATION);
        // Subtraction avoids overflow even for a deliberately impossible test budget.
        if (available < DISK_HEADROOM || available - DISK_HEADROOM < growth)
            throw new OfficeWorkBudget.Exceeded(OfficeWorkBudget.Limit.NO_SPACE);
    }
    private OfficeResourcePolicy() {}
}
