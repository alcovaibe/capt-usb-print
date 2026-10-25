package com.cryonum.captusbprint.office;

import java.nio.file.Files;
import java.nio.file.Path;

/** Real filesystem links: deleting an owned tree must never traverse the outside sentinel. */
public final class OfficePrivateFilesCheck {
    private static int checks;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("CLEANUP_"+checks);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("capt-office-cleanup-");
        try {
            Path outside = Files.createDirectory(base.resolve("outside"));
            Path sentinel = Files.writeString(outside.resolve("keep"), "synthetic sentinel");
            Path owned = Files.createDirectory(base.resolve("owned"));
            Files.createDirectories(owned.resolve("nested/deep"));
            Files.writeString(owned.resolve("nested/deep/source"), "synthetic source");
            Files.createSymbolicLink(owned.resolve("directory-link"), outside);
            Files.createSymbolicLink(owned.resolve("file-link"), sentinel);
            Files.createSymbolicLink(owned.resolve("dangling-link"), base.resolve("missing"));
            check(OfficePrivateFiles.deleteTree(owned.toFile()));
            check(Files.notExists(owned));
            check(Files.readString(sentinel).equals("synthetic sentinel"));
            check(OfficePrivateFiles.deleteTree(owned.toFile()));
            Path rootLink = Files.createSymbolicLink(base.resolve("root-link"), outside);
            check(OfficePrivateFiles.deleteTree(rootLink.toFile()));
            check(Files.notExists(rootLink));
            check(Files.readString(sentinel).equals("synthetic sentinel"));
        } finally { check(OfficePrivateFiles.deleteTree(base.toFile())); }
        System.out.println("CAPT_OFFICE_PRIVATE_CLEANUP_PASSED checks="+checks+" (no symbolic-link traversal)");
    }
}
