package org.javacs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.tools.JavaFileObject;
import org.javacs.FileStore;
import org.javacs.CompilerProvider;

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

    public static Optional<JavaFileObject> resolveTargetSource(
            CompilerProvider compiler, Path file, String className) {
        var target = findExternalSource(file, className).or(() -> compiler.findAnywhere(className));
        if (target.isEmpty()) return Optional.empty();
        return Optional.of(materialize(target.get()));
    }

    public static Optional<String> packageNameFromSource(Path path) {
        var contents = FileStore.contents(path);
        if (contents == null) return Optional.empty();
        var matcher = PACKAGE_LINE.matcher(contents);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    public static Optional<JavaFileObject> findExternalSource(Path file, String className) {
        if (!isJarDerived(file)) {
            return Optional.empty();
        }
        var pkg = packageName(className);
        var topLevel = topLevelName(className, pkg);
        var relative =
                pkg.isEmpty()
                        ? Paths.get(topLevel + ".java")
                        : Paths.get(pkg.replace('.', '/')).resolve(topLevel + ".java");

        var jarRoot = externalJarRoot(file);
        if (jarRoot != null) {
            var candidate = jarRoot.resolve(relative);
            if (Files.exists(candidate)) {
                return Optional.of(new SourceFileObject(candidate));
            }
        }

        return Optional.empty();
    }

    public static boolean isJarDerived(Path path) {
        var uri = path.toUri().toString();
        if (FileStore.isExternalUri(uri)) {
            return true;
        }
        return path.toString().replace('\\', '/').contains("/jls-sources/");
    }

    public static Path externalJarRoot(Path path) {
        var normalized = path.toString().replace('\\', '/');
        var marker = "/jls-sources/";
        var idx = normalized.indexOf(marker);
        if (idx == -1) return null;
        var next = normalized.indexOf('/', idx + marker.length());
        if (next == -1) return null;
        return Paths.get(normalized.substring(0, next));
    }

    public static String packageName(String className) {
        var m = PACKAGE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    public static String topLevelName(String className, String pkg) {
        var start = pkg.isEmpty() ? 0 : pkg.length() + 1;
        if (start >= className.length()) {
            return "";
        }
        var remainder = className.substring(start).replace('$', '.');
        var dot = remainder.indexOf('.');
        return dot == -1 ? remainder : remainder.substring(0, dot);
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

    private static final Pattern PACKAGE_EXTRACTOR =
            Pattern.compile("^([a-z][_a-zA-Z0-9]*\\.)*[a-z][_a-zA-Z0-9]*");
    private static final Pattern PACKAGE_LINE =
            Pattern.compile("(?m)^\\s*package\\s+([^;\\s]+)\\s*;");
}
