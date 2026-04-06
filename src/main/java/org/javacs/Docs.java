package org.javacs;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.tools.*;

public class Docs {

    /** File manager with source-path + platform sources, which we will use to look up individual source files */
    final SourceFileManager fileManager = new SourceFileManager();

    Docs(Set<Path> docPath) {
        var srcZipFile = findSrcZip();
        // Path to source .jars + src.zip
        var sourcePath = new ArrayList<Path>(docPath);
        if (srcZipFile != NOT_FOUND) {
            sourcePath.add(srcZipFile);
        }
        try {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourcePath);
            if (srcZipFile != NOT_FOUND) {
                var srcZipVirtualPath = srcZipVirtualPath(srcZipFile);
                if (srcZipVirtualPath != NOT_FOUND) {
                    fileManager.setLocationFromPaths(StandardLocation.MODULE_SOURCE_PATH, Set.of(srcZipVirtualPath));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static final Path NOT_FOUND = Paths.get("");
    private static final Map<Path, Path> SRC_ZIP_CACHE = new ConcurrentHashMap<>();
    private static final Map<Path, Path> SRC_ZIP_VIRTUAL_PATH_CACHE = new ConcurrentHashMap<>();

    private static Path srcZipVirtualPath(Path srcZipFile) {
        var cached = SRC_ZIP_VIRTUAL_PATH_CACHE.get(srcZipFile);
        if (cached != null) {
            CacheAudit.hit("docs.src_zip_virtual_path");
            return cached;
        }
        CacheAudit.miss("docs.src_zip_virtual_path");
        Path virtualPath;
        try {
            var fs = FileSystems.newFileSystem(srcZipFile, Docs.class.getClassLoader());
            virtualPath = fs.getPath("/");
        } catch (IOException e) {
            LOG.warning("Failed to create virtual filesystem for " + srcZipFile + ": " + e.getMessage());
            virtualPath = NOT_FOUND;
        }
        SRC_ZIP_VIRTUAL_PATH_CACHE.put(srcZipFile, virtualPath);
        CacheAudit.load("docs.src_zip_virtual_path");
        CacheAudit.store("docs.src_zip_virtual_path");
        return virtualPath;
    }

    static Path findSrcZip() {
        var javaHome = JavaHomeHelper.javaHome();
        var cached = SRC_ZIP_CACHE.get(javaHome);
        if (cached != null) {
            CacheAudit.hit("docs.find_src_zip");
            return cached;
        }
        CacheAudit.miss("docs.find_src_zip");
        String[] locations = {
            "lib/src.zip", "src.zip", "libexec/openjdk.jdk/Contents/Home/lib/src.zip"
        };
        for (var rel : locations) {
            var abs = javaHome.resolve(rel);
            if (Files.exists(abs)) {
                LOG.info("Found " + abs);
                SRC_ZIP_CACHE.put(javaHome, abs);
                CacheAudit.load("docs.find_src_zip");
                CacheAudit.store("docs.find_src_zip");
                return abs;
            }
        }
        LOG.warning("Couldn't find src.zip in " + javaHome);
        SRC_ZIP_CACHE.put(javaHome, NOT_FOUND);
        CacheAudit.load("docs.find_src_zip");
        CacheAudit.store("docs.find_src_zip");
        return NOT_FOUND;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
