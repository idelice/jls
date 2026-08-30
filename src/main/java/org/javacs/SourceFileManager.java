package org.javacs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import javax.tools.*;

class SourceFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    SourceFileManager() {
        super(createDelegateFileManager());
    }

    private static StandardJavaFileManager createDelegateFileManager() {
        var compiler = ServiceLoader.load(JavaCompiler.class).iterator().next();
        return compiler.getStandardFileManager(SourceFileManager::logError, null, Charset.defaultCharset());
    }

    private static void logError(Diagnostic<?> error) {
        LOG.warning(error.getMessage(null));
    }

    @Override
    public Iterable<JavaFileObject> list(
            Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        var listed = super.list(location, packageName, kinds, recurse);
        if (location != StandardLocation.SOURCE_PATH || !kinds.contains(JavaFileObject.Kind.SOURCE)) {
            return listed;
        }

        var result = new LinkedHashMap<java.net.URI, JavaFileObject>();
        for (var source : listed) {
            var path = filePath(source);
            if (path != null && !FileStore.contains(path)) continue;
            if (path != null && (FileStore.activeDocument(path) != null || FileStore.isDirty(path))) {
                source = new SourceFileObject(path);
            }
            result.put(source.toUri(), source);
        }

        // Native lookup cannot see a newly-created editor buffer until it exists on disk.
        for (var path : FileStore.activeDocuments()) {
            if (Files.exists(path) || !isOnSourcePath(path)) continue;
            var sourcePackage = sourcePackage(path);
            if (sourcePackage == null) continue;
            var packageMatches = recurse
                    ? sourcePackage.equals(packageName) || sourcePackage.startsWith(packageName + ".")
                    : sourcePackage.equals(packageName);
            if (packageMatches) result.putIfAbsent(path.toUri(), new SourceFileObject(path));
        }
        return result.values();
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (location == StandardLocation.SOURCE_PATH && file instanceof SourceFileObject source) {
            return binaryName(source.path);
        }
        return super.inferBinaryName(location, file);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind)
            throws IOException {
        var source = super.getJavaFileForInput(location, className, kind);
        if (location != StandardLocation.SOURCE_PATH || kind != JavaFileObject.Kind.SOURCE) return source;
        var path = filePath(source);
        if (path != null && (FileStore.activeDocument(path) != null || FileStore.isDirty(path))) {
            return new SourceFileObject(path);
        }
        if (source != null) return source;
        for (var candidate : FileStore.activeDocuments()) {
            if (!Files.exists(candidate)
                    && isOnSourcePath(candidate)
                    && className.equals(binaryName(candidate))) {
                return new SourceFileObject(candidate);
            }
        }
        return null;
    }

    @Override
    public boolean contains(Location location, FileObject file) throws IOException {
        if (location == StandardLocation.SOURCE_PATH && file instanceof SourceFileObject source)
            return isOnSourcePath(source.path);
        return super.contains(location, file);
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        if (a instanceof SourceFileObject || b instanceof SourceFileObject)
            return a.toUri().normalize().equals(b.toUri().normalize());
        return super.isSameFile(a, b);
    }

    private Path filePath(FileObject file) {
        if (file == null || !"file".equalsIgnoreCase(file.toUri().getScheme())) return null;
        return Path.of(file.toUri()).toAbsolutePath().normalize();
    }

    private boolean isOnSourcePath(Path path) {
        var normalized = path.toAbsolutePath().normalize();
        var roots = fileManager.getLocationAsPaths(StandardLocation.SOURCE_PATH);
        if (roots == null) return false;
        for (var root : roots) if (normalized.startsWith(root.toAbsolutePath().normalize())) return true;
        return false;
    }

    private String binaryName(Path path) {
        var packageName = sourcePackage(path);
        if (packageName == null) return null;
        var fileName = path.getFileName().toString();
        var className = fileName.substring(0, fileName.length() - JavaFileObject.Kind.SOURCE.extension.length());
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    private String sourcePackage(Path path) {
        try {
            return StringSearch.packageName(path);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    void setLocation(Location location, Iterable<? extends File> files) throws IOException {
        fileManager.setLocation(location, files);
    }

    void setLocationFromPaths(Location location, Collection<? extends Path> searchpath) throws IOException {
        fileManager.setLocationFromPaths(location, searchpath);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
