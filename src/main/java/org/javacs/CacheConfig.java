package org.javacs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

final class CacheConfig {
    private static final Logger LOG = Logger.getLogger("main");
    private static volatile Path cacheDir = defaultCacheDir();
    private static volatile boolean userOverride = false;

    private static Path defaultCacheDir() {
        var xdgCacheHome = System.getenv("XDG_CACHE_HOME");
        if (xdgCacheHome != null && !xdgCacheHome.isBlank()) {
            return Paths.get(xdgCacheHome, "jls");
        }
        return Paths.get(System.getProperty("user.home"), ".cache", "jls");
    }

    static Path cacheDir() {
        ensureDir(cacheDir);
        LOG.fine("Cache dir: " + cacheDir);
        return cacheDir;
    }

    static void setCacheDir(Path dir) {
        if (dir == null) return;
        cacheDir = dir.toAbsolutePath().normalize();
        userOverride = true;
        ensureDir(cacheDir);
    }

    static void setCacheDirIfUnset(Path dir) {
        if (dir == null || userOverride) return;
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
