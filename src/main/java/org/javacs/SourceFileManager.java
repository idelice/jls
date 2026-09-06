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
    private final java.util.function.Predicate<String> workspaceType;

    SourceFileManager() { this(name -> false); }

    SourceFileManager(java.util.function.Predicate<String> workspaceType) {
        super(createDelegateFileManager());
        this.workspaceType = workspaceType;
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
        if (location == StandardLocation.CLASS_PATH && kinds.contains(JavaFileObject.Kind.CLASS)) {
            var external = new ArrayList<JavaFileObject>();
            for (var file : listed) {
                if (file.getKind() != JavaFileObject.Kind.CLASS
                        || !workspaceType.test(super.inferBinaryName(location, file))) external.add(file);
            }
            return external;
        }
        if (location != StandardLocation.SOURCE_PATH || !kinds.contains(JavaFileObject.Kind.SOURCE)) return listed;

        var result = new LinkedHashMap<java.net.URI, JavaFileObject>();
        for (var source : listed) {
            if (isModuleDeclaration(source)) continue;
            var path = filePath(source);
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
        var sources = new ArrayList<>(result.values());
        for (var source : result.values()) {
            var path = filePath(source);
            if (path == null || !FileStore.contains(path)) continue;
            var primary = binaryName(path);
            var prefix = packageName.isEmpty() ? "" : packageName + ".";
            for (var declared : JavaCompilerService.declaredTypes(path)) {
                if (!declared.equals(primary) && declared.startsWith(prefix)
                        && declared.indexOf('.', prefix.length()) < 0) sources.add(new AuxiliarySource(path, declared));
            }
        }
        return sources;
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof AuxiliarySource source) return source.binaryName;
        if (location == StandardLocation.SOURCE_PATH && file instanceof SourceFileObject source) {
            return binaryName(source.path);
        }
        return super.inferBinaryName(location, file);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind)
            throws IOException {
        if (location == StandardLocation.CLASS_PATH && kind == JavaFileObject.Kind.CLASS
                && workspaceType.test(className)) return null;
        if (location == StandardLocation.SOURCE_PATH && "module-info".equals(className)) return null;
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

    @Override public JavaFileObject getJavaFileForOutput(Location location, String name,
            JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        throw new IOException("Source analysis does not emit files: " + name);
    }

    @Override public FileObject getFileForOutput(Location location, String packageName,
            String relativeName, FileObject sibling) throws IOException {
        throw new IOException("Source analysis does not emit files: " + relativeName);
    }

    /**
      * A reactor spans many named modules, but one javac task has a single flat source path and can
      * only compile one module from source. Reading a module declaration would put the whole task in
      * module mode, where the dependencies on the class path stop being readable and types resolved
      * from two modules stop matching. Analysis therefore treats the workspace as unnamed code; the
      * cost is that module-visibility errors are not reported.
      */
    private static boolean isModuleDeclaration(FileObject file) {
        var name = file.getName();
        return name.endsWith("module-info.java") || name.endsWith("module-info.class");
    }

    private static final class AuxiliarySource extends SourceFileObject {
        final String binaryName;
        AuxiliarySource(Path path, String binaryName) { super(path); this.binaryName = binaryName; }
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
        // FileStore caches the package per file; reading it off disk here would re-read and
        // re-scan every source on each package listing during a compile.
        return FileStore.packageName(path);
    }

    void setLocation(Location location, Iterable<? extends File> files) throws IOException {
        fileManager.setLocation(location, files);
    }

    void setLocationFromPaths(Location location, Collection<? extends Path> searchpath) throws IOException {
        fileManager.setLocationFromPaths(location, searchpath);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
