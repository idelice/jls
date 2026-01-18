package org.javacs;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
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
    private static Path cacheSrcZipVirtualPath;

    private static Path srcZipVirtualPath(Path srcZipFile) {
        if (cacheSrcZipVirtualPath == null) {
            try {
                var fs = FileSystems.newFileSystem(srcZipFile, Docs.class.getClassLoader());
                cacheSrcZipVirtualPath = fs.getPath("/");
            } catch (IOException e) {
                LOG.warning("Failed to create virtual filesystem for " + srcZipFile + ": " + e.getMessage());
                cacheSrcZipVirtualPath = NOT_FOUND;
            }
        }
        return cacheSrcZipVirtualPath;
    }

    static Path findSrcZip() {
        var javaHome = JavaHomeHelper.javaHome();
        String[] locations = {
            "lib/src.zip", "src.zip", "libexec/openjdk.jdk/Contents/Home/lib/src.zip"
        };
        for (var rel : locations) {
            var abs = javaHome.resolve(rel);
            if (Files.exists(abs)) {
                LOG.info("Found " + abs);
                return abs;
            }
        }
        LOG.warning("Couldn't find src.zip in " + javaHome);
        return NOT_FOUND;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
