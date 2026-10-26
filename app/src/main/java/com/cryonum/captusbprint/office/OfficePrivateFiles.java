package com.cryonum.captusbprint.office;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Cleanup of an owned private tree after readers/worker stop. Never follows symbolic links. */
public final class OfficePrivateFiles {
    private OfficePrivateFiles() {}
    public static boolean deleteTree(File directory) {
        Path root = directory.toPath();
        try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return true;
            // Default walkFileTree does NOT use FOLLOW_LINKS. A link itself is unlinked.
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
            });
            return Files.notExists(root, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException ignored) {
            // No paths/provider details/document content in logs or caller-visible errors.
            return false;
        }
    }
}
