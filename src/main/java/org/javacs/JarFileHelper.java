package org.javacs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.logging.Logger;
import javax.tools.JavaFileObject;

public class JarFileHelper {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Path TMP = Paths.get(System.getProperty("java.io.tmpdir"), "jls-sources");

    public static Path extractedSourcesRoot() {
        return TMP;
    }

    public static JavaFileObject materialize(JavaFileObject source) {
        try {
            var uri = source.toUri();
            if (uri == null) return source;
            if ("jar".equalsIgnoreCase(uri.getScheme()) || "jrt".equalsIgnoreCase(uri.getScheme())) {
                var extracted = extractIfNeeded(uri);
                if (extracted != null && "file".equalsIgnoreCase(extracted.getScheme())) {
                    return new SourceFileObject(Paths.get(extracted));
                }
            }
        } catch (RuntimeException e) {
            // fall through to original source
        }
        return source;
    }

    public static boolean isJdkSource(URI uri) {
        if (uri == null) return false;
        var scheme = uri.getScheme();
        if ("jrt".equalsIgnoreCase(scheme)) return true;
        if ("jar".equalsIgnoreCase(scheme)) {
            var s = uri.toString().replace('\\', '/');
            return s.contains("/lib/src.zip!/") && s.contains("/java.base/");
        }
        if ("file".equalsIgnoreCase(scheme)) {
            var path = Paths.get(uri).toString().replace('\\', '/');
            return path.contains("/jls-sources/src.zip/");
        }
        return false;
    }

    public static URI extractIfNeeded(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            return uri;
        }
        if (uri.getScheme().equals("jar")) {
            return extractJarUri(uri);
        }
        if (uri.getScheme().equals("jrt")) {
            return extractJrtUri(uri);
        }
        return uri;
    }

    private static URI extractJarUri(URI uri) {
        try {
            // jar:file:///path/to.jar!/com/example/Foo.java
            var s = uri.toString();
            var separator = s.indexOf("!/");
            if (separator == -1) return uri;

            var jarUri = URI.create(s.substring(4, separator));
            var entryName = s.substring(separator + 2);

            var jarPath = Paths.get(jarUri);
            var dest = TMP.resolve(jarPath.getFileName() + "/" + entryName);

            if (Files.exists(dest)) {
                return dest.toRealPath().toUri();
            }

            Files.createDirectories(dest.getParent());
            try (var fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
                var file = fs.getPath(entryName);
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return dest.toRealPath().toUri();
        } catch (IOException e) {
            LOG.warning("Failed to extract " + uri + ": " + e.getMessage());
            return uri;
        }
    }

    private static URI extractJrtUri(URI uri) {
        // Example: jrt:/java.base/java/lang/String.java
        // We mount the JRT filesystem and copy the entry to the same TMP root.
        try (var fs = FileSystems.newFileSystem(URI.create("jrt:/"), java.util.Map.of())) {
            var pathInJrt = fs.getPath(uri.getPath());
            // Path layout: /java.base/java/lang/String.java
            var dest = TMP.resolve("src.zip").resolve(pathInJrt.toString().substring(1));
            if (Files.exists(dest)) {
                return dest.toRealPath().toUri();
            }
            Files.createDirectories(dest.getParent());
            Files.copy(pathInJrt, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest.toRealPath().toUri();
        } catch (IOException e) {
            LOG.warning("Failed to extract " + uri + ": " + e.getMessage());
            return uri;
        }
    }
}
