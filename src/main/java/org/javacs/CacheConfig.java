package org.javacs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

final class CacheConfig {
    private static final Logger LOG = Logger.getLogger("main");
    private static volatile Path cacheDir = defaultCacheDir();

    private static Path defaultCacheDir() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "jls-cache");
    }

    static Path cacheDir() {
        ensureDir(cacheDir);
        return cacheDir;
    }

    static void setCacheDir(Path dir) {
        if (dir == null) return;
        cacheDir = dir.toAbsolutePath().normalize();
        ensureDir(cacheDir);
    }

    private static void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warning("Failed to create cache dir " + dir + ": " + e.getMessage());
        }
    }
}
