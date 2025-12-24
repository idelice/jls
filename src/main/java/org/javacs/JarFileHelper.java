package org.javacs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.logging.Logger;

public class JarFileHelper {
    private static final Logger LOG = Logger.getLogger("main");
    private static final Path TMP = Paths.get(System.getProperty("java.io.tmpdir"), "jls-sources");

    public static URI extractIfNeeded(URI uri) {
        if (uri == null || uri.getScheme() == null || !uri.getScheme().equals("jar")) {
            return uri;
        }
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
                return dest.toUri();
            }
            
            Files.createDirectories(dest.getParent());
            try (var fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
                var file = fs.getPath(entryName);
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return dest.toUri();
        } catch (IOException e) {
            LOG.warning("Failed to extract " + uri + ": " + e.getMessage());
            return uri;
        }
    }
}
