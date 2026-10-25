package com.cryonum.captusbprint.office;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** Host filesystem and proc parsing checks; deliberately no Office parser or large allocations. */
public final class OfficeWorkBudgetCheck {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
        checks++;
    }
    private static void limit(OfficeWorkBudget.Limit expected, Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + expected); }
        catch (OfficeWorkBudget.Exceeded error) { check(error.limit == expected); }
    }
    public static void main(String[] args) throws Exception {
        OfficeWorkBudget budget = new OfficeWorkBudget(10, 2048, 5, 8, 10);
        budget.checkTime(0); budget.checkTime(9);
        limit(OfficeWorkBudget.Limit.TIME, () -> budget.checkTime(10));
        limit(OfficeWorkBudget.Limit.TIME, () -> budget.checkTime(-1));
        budget.checkRss(2048);
        limit(OfficeWorkBudget.Limit.MEMORY, () -> budget.checkRss(2049));
        limit(OfficeWorkBudget.Limit.OBSERVATION, () -> budget.checkRss(0));
        check(OfficeWorkBudget.parseRss("Name:\ttest\nVmSize: 999 kB\nVmRSS:\t2 kB\n") == 2048);
        for (String invalid : new String[] { "", "VmSize: 4 kB", "VmRSS: -1 kB", "VmRSS: 0 kB",
                "VmRSS: 12 MB", "VmRSS: nope kB", "VmRSS: 9007199254740992 kB", "VmRSS: 1 kB junk" })
            limit(OfficeWorkBudget.Limit.OBSERVATION, () -> OfficeWorkBudget.parseRss(invalid));
        Path parent = Files.createTempDirectory("capt-office-budget-");
        Path root = Files.createDirectory(parent.resolve("work"));
        Path first = root.resolve("one");
        Path second = root.resolve("two");
        Path extra = root.resolve("extra");
        Path link = root.resolve("link");
        Path outside = parent.resolve("outside");
        try {
            Files.write(first, new byte[3]); Files.write(second, new byte[2]);
            check(budget.checkDisk(root.toFile(), 8) == 5);
            limit(OfficeWorkBudget.Limit.NO_SPACE, () -> budget.checkDisk(root.toFile(), 7));
            Files.write(extra, new byte[1]);
            limit(OfficeWorkBudget.Limit.DISK, () -> budget.checkDisk(root.toFile(), 100));
            Files.delete(extra);
            OfficeWorkBudget fileCount = new OfficeWorkBudget(10, 2048, 5, 8, 2);
            limit(OfficeWorkBudget.Limit.FILE_COUNT, () -> fileCount.checkDisk(root.toFile(), 100));
            Files.write(outside, new byte[] {42});
            Files.createSymbolicLink(link, outside);
            limit(OfficeWorkBudget.Limit.UNSAFE_PATH, () -> budget.checkDisk(root.toFile(), 100));
            check(Files.readAllBytes(outside)[0] == 42);
            Files.delete(link);
            try (RandomAccessFile sparse = new RandomAccessFile(extra.toFile(), "rw")) { sparse.setLength(1024 * 1024); }
            limit(OfficeWorkBudget.Limit.DISK, () -> budget.checkDisk(root.toFile(), 100));
            try { new OfficeWorkBudget(0, 1, 1, 0, 1); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { checks++; }
        } finally {
            for (Path file : new Path[] {link, extra, first, second, outside}) Files.deleteIfExists(file);
            Files.deleteIfExists(root); Files.deleteIfExists(parent);
        }
        System.out.println("CAPT_OFFICE_WORK_BUDGET_PASSED checks=" + checks);
    }
    private OfficeWorkBudgetCheck() {}
}
