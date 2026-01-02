package org.javacs;

import java.io.*;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.lang.model.element.TypeElement;
import com.google.gson.reflect.TypeToken;
import org.javacs.index.WorkspaceIndex;
import org.javacs.lsp.DidChangeTextDocumentParams;
import org.javacs.lsp.DidCloseTextDocumentParams;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.TextDocumentContentChangeEvent;
import static org.javacs.JsonHelper.GSON;

public class FileStore {

    private static final Set<Path> workspaceRoots = new HashSet<>();

    private static final Map<Path, VersionedContent> activeDocuments = new HashMap<>();

    /** javaSources[file] is the javaSources time of a .java source file. */
    // TODO organize by package name for speed of list(...)
    private static final TreeMap<Path, Info> javaSources = new TreeMap<>();

    private static final ThreadLocal<Set<Path>> activeSourceRoots = new ThreadLocal<>();

    private static final ScheduledExecutorService CACHE_WRITER =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "jls-cache-writer"));
    private static final ScheduledExecutorService BACKGROUND_INDEXER =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "jls-indexer"));
    private static final int CACHE_WRITE_DELAY_MS = 1000;
    private static boolean cacheWriteScheduled = false;
    private static Path cacheFile = null;
    private static Path indexCacheFile = null;

    private static long workspaceVersion = 0;

    private static final Set<String> SKIP_DIR_NAMES =
            Set.of(".git", ".idea", ".gradle", "node_modules", "target", "build", "dist", "out");

    private static class Info {
        final Instant modified;
        final String packageName;

        Info(Instant modified, String packageName) {
            this.modified = modified;
            this.packageName = packageName;
        }
    }

    static void setWorkspaceRoots(Set<Path> newRoots) {
        newRoots = normalize(newRoots);
        synchronized (FileStore.class) {
            workspaceRoots.clear();
            workspaceRoots.addAll(newRoots);
        }
        initCache(newRoots);
        var cacheLoaded = loadCache();
        if (cacheLoaded > 0) {
            LOG.info(String.format("Workspace cache hit: loaded %,d java source entries", cacheLoaded));
        } else {
            LOG.info("Workspace cache miss: no valid entries loaded");
        }
        var indexLoaded = WorkspaceIndex.loadCache(newRoots);
        if (indexLoaded > 0) {
            LOG.info(String.format("Workspace index cache hit: loaded %,d files", indexLoaded));
        } else {
            LOG.info("Workspace index cache miss: no valid entries loaded");
        }
        var toAdd = new ArrayList<Path>(newRoots);
        CompletableFuture.runAsync(() -> {
            var started = Instant.now();
            var count = new AtomicInteger();
            var allFiles = new ArrayList<Path>();
            for (var root : toAdd) {
                addFiles(root, count, allFiles);
            }
            var elapsed = java.time.Duration.between(started, Instant.now()).toMillis();
            LOG.fine(String.format("Scanned %,d java files in %,d ms", count.get(), elapsed));
            scheduleBackgroundIndex(allFiles);
            scheduleCacheSave();
        });
    }

    private static Set<Path> normalize(Set<Path> newRoots) {
        var normalize = new HashSet<Path>();
        for (var root : newRoots) {
            normalize.add(root.toAbsolutePath().normalize());
        }
        return normalize;
    }

    private static void addFiles(Path root, AtomicInteger count, List<Path> out) {
        var files = new ArrayList<Path>();
        try {
            Files.walkFileTree(root, new FindJavaSources(files, count));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (!files.isEmpty()) {
            out.addAll(files);
        }
    }

    static class FindJavaSources extends SimpleFileVisitor<Path> {
        private final List<Path> files;
        private final AtomicInteger count;

        FindJavaSources(List<Path> files, AtomicInteger count) {
            this.files = files;
            this.count = count;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (attrs.isSymbolicLink()) {
                LOG.warning("Don't check " + dir + " for java sources");
                return FileVisitResult.SKIP_SUBTREE;
            }
            var name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (SKIP_DIR_NAMES.contains(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes _attrs) {
            if (isJavaFile(file)) {
                files.add(file);
                count.incrementAndGet();
            }
            return FileVisitResult.CONTINUE;
        }
    }

    public static synchronized Collection<Path> all() {
        return new ArrayList<>(javaSources.keySet());
    }

    static synchronized void reset() {
        activeDocuments.clear();
        workspaceRoots.clear();
        javaSources.clear();
        WorkspaceIndex.clear();
        workspaceVersion++;
    }

    static synchronized List<Path> list(String packageName) {
        var list = new ArrayList<Path>();
        for (var file : javaSources.keySet()) {
            if (javaSources.get(file).packageName.equals(packageName)) {
                if (isInActiveSourceRoots(file)) {
                    list.add(file);
                }
            }
        }
        return list;
    }

    public static synchronized Set<Path> sourceRoots() {
        var roots = new HashSet<Path>();
        for (var file : javaSources.keySet()) {
            var root = sourceRoot(file);
            if (root != null) {
                roots.add(root);
            }
        }
        return roots;
    }

    public static synchronized Path sourceRoot(Path file) {
        var info = javaSources.get(file);
        var parts = info.packageName.split("\\.");
        var dir = file.getParent();
        for (var i = parts.length - 1; i >= 0; i--) {
            var end = parts[i];
            if (dir.endsWith(end)) {
                dir = dir.getParent();
            } else {
                return null;
            }
        }
        return dir;
    }

    static synchronized boolean contains(Path file) {
        return isJavaFile(file) && javaSources.containsKey(file);
    }

    public static synchronized Instant modified(Path file) {
        // If file is open, use last in-memory modification time
        if (activeDocuments.containsKey(file)) {
            return activeDocuments.get(file).modified;
        }
        // If we've never checked before, look up modified time on disk
        if (!javaSources.containsKey(file)) {
            readInfoFromDisk(file);
        }
        // Look up modified time from cache
        var info = javaSources.get(file);
        if (info == null) {
            return null;
        }
        return info.modified;
    }

    static synchronized String packageName(Path file) {
        // If we've never checked before, look up package name on disk
        if (!javaSources.containsKey(file)) {
            readInfoFromDisk(file);
        }
        // Look up package name from cache
        var info = javaSources.get(file);
        if (info == null) {
            return null;
        }
        return info.packageName;
    }

    public static synchronized String suggestedPackageName(Path file) {
        // Look in each parent directory of file
        for (var dir = file.getParent(); dir != null; dir = dir.getParent()) {
            // Try to find a sibling with a package declaration
            for (var sibling : javaSourcesIn(dir)) {
                if (sibling.equals(file)) continue;
                var packageName = packageName(sibling);
                if (packageName == null || packageName.isBlank()) continue;
                var relativePath = dir.relativize(file.getParent());
                var relativePackage = relativePath.toString().replace(File.separatorChar, '.');
                if (!relativePackage.isEmpty()) {
                    packageName = packageName + "." + relativePackage;
                }
                return packageName;
            }
        }
        return "";
    }

    private static synchronized List<Path> javaSourcesIn(Path dir) {
        var tail = javaSources.tailMap(dir, false);
        var list = new ArrayList<Path>();
        for (var file : tail.keySet()) {
            if (!file.startsWith(dir)) break;
            list.add(file);
        }
        return list;
    }

    static synchronized void externalCreate(Path file) {
        readInfoFromDisk(file);
    }

    static synchronized void externalChange(Path file) {
        readInfoFromDisk(file);
    }

    static synchronized void externalDelete(Path file) {
        javaSources.remove(file);
        WorkspaceIndex.removeFile(file);
        workspaceVersion++;
        scheduleCacheSave();
    }

    private static synchronized void readInfoFromDisk(Path file) {
        try {
            var time = Files.getLastModifiedTime(file).toInstant();
            var contents = Files.readString(file);
            var packageName = StringSearch.packageName(contents);
            var tokens = WorkspaceIndex.tokenizeContents(contents);
            javaSources.put(file, new Info(time, packageName));
            WorkspaceIndex.updateFileTokens(file, tokens, time.toEpochMilli());
            workspaceVersion++;
            scheduleCacheSave();
        } catch (NoSuchFileException | CharacterCodingException e) {
            LOG.warning(e.getMessage());
            javaSources.remove(file);
            WorkspaceIndex.removeFile(file);
            workspaceVersion++;
            scheduleCacheSave();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void open(DidOpenTextDocumentParams params) {
        if (!isJavaFile(params.textDocument.uri)) return;
        var document = params.textDocument;
        var file = Paths.get(document.uri);
        activeDocuments.put(file, new VersionedContent(document.text, document.version));
        WorkspaceIndex.updateFile(file);
        scheduleCacheSave();
    }

    static void change(DidChangeTextDocumentParams params) {
        if (!isJavaFile(params.textDocument.uri)) return;
        var document = params.textDocument;
        var file = Paths.get(document.uri);
        var existing = activeDocuments.get(file);
        if (document.version <= existing.version) {
            LOG.warning("Ignored change with version " + document.version + " <= " + existing.version);
            return;
        }
        var newText = existing.content;
        for (var change : params.contentChanges) {
            if (change.range == null) newText = change.text;
            else newText = patch(newText, change);
        }
        activeDocuments.put(file, new VersionedContent(newText, document.version));
        WorkspaceIndex.updateFile(file);
        scheduleCacheSave();
    }

    static void close(DidCloseTextDocumentParams params) {
        if (!isJavaFile(params.textDocument.uri)) return;
        var file = Paths.get(params.textDocument.uri);
        activeDocuments.remove(file);
        WorkspaceIndex.updateFile(file);
        scheduleCacheSave();
    }

    static Set<Path> activeDocuments() {
        return activeDocuments.keySet();
    }

    public static synchronized Integer documentVersion(Path file) {
        var doc = activeDocuments.get(file);
        if (doc == null) return null;
        return doc.version;
    }

    public static String contents(Path file) {
        if (!isJavaFile(file)) {
            throw new RuntimeException(file + " is not a java file");
        }
        if (activeDocuments.containsKey(file)) {
            return activeDocuments.get(file).content;
        }
        try {
            return Files.readString(file);
        } catch (NoSuchFileException e) {
            LOG.warning(e.getMessage());
            return "";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static InputStream inputStream(Path file) {
        var uri = file.toUri();
        if (activeDocuments.containsKey(uri)) {
            var string = activeDocuments.get(uri).content;
            var bytes = string.getBytes();
            return new ByteArrayInputStream(bytes);
        }
        try {
            return Files.newInputStream(file);
        } catch (NoSuchFileException e) {
            LOG.warning(e.getMessage());
            byte[] bs = {};
            return new ByteArrayInputStream(bs);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static BufferedReader bufferedReader(Path file) {
        var uri = file.toUri();
        if (activeDocuments.containsKey(uri)) {
            var string = activeDocuments.get(uri).content;
            return new BufferedReader(new StringReader(string));
        }
        try {
            return Files.newBufferedReader(file);
        } catch (NoSuchFileException e) {
            LOG.warning(e.getMessage());
            return new BufferedReader(new StringReader(""));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static BufferedReader lines(Path file) {
        return bufferedReader(file);
    }

    /** Convert from line/column (1-based) to offset (0-based) */
    static int offset(String contents, int line, int column) {
        line--;
        column--;
        int cursor = 0;
        while (line > 0) {
            if (contents.charAt(cursor) == '\n') {
                line--;
            }
            cursor++;
        }
        return cursor + column;
    }

    private static String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
            var range = change.range;
            var reader = new BufferedReader(new StringReader(sourceText));
            var writer = new StringWriter();

            // Skip unchanged lines
            int line = 0;

            while (line < range.start.line) {
                writer.write(reader.readLine() + '\n');
                line++;
            }

            // Skip unchanged chars
            for (int character = 0; character < range.start.character; character++) {
                writer.write(reader.read());
            }

            // Write replacement text
            writer.write(change.text);

            // Skip replaced text
            if (change.range.start.line == change.range.end.line) {
                int chars = change.range.end.character - change.range.start.character;
                reader.skip(chars);
            } else {
                int lines = change.range.end.line - change.range.start.line;
                int chars = change.range.end.character;
                for (int lineSkip = 0; lineSkip < lines; lineSkip++) {
                    reader.readLine();
                }
                reader.skip(chars);
            }

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1) return writer.toString();
                else writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isJavaFile(Path file) {
        var name = file.getFileName().toString();
        // We hide module-info.java from javac, because when javac sees module-info.java
        // it goes into "module mode" and starts looking for classes on the module class path.
        // This becomes evident when javac starts recompiling *way too much* on each task,
        // because it doesn't realize there are already up-to-date .class files.
        // The better solution would be for java-language server to detect the presence of module-info.java,
        // and go into its own "module mode" where it infers a module source path and a module class path.
        return name.endsWith(".java") && !Files.isDirectory(file) && !name.equals("module-info.java");
    }

    static boolean isJavaFile(URI uri) {
        return uri.getScheme().equals("file") && isJavaFile(Paths.get(uri));
    }

    static Optional<Path> findDeclaringFile(TypeElement el) {
        var qualifiedName = el.getQualifiedName().toString();
        var packageName = StringSearch.mostName(qualifiedName);
        var className = StringSearch.lastName(qualifiedName);
        // Fast path: look for text `class Foo` in file Foo.java
        for (var f : list(packageName)) {
            if (f.getFileName().toString().equals(className) && StringSearch.containsType(f, el)) {
                return Optional.of(f);
            }
        }
        // Slow path: look for text `class Foo` in any file in package
        for (var f : list(packageName)) {
            if (StringSearch.containsType(f, el)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    static void setActiveSourceRoots(Set<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            activeSourceRoots.remove();
            return;
        }
        activeSourceRoots.set(roots);
    }

    static void clearActiveSourceRoots() {
        activeSourceRoots.remove();
    }

    private static boolean isInActiveSourceRoots(Path file) {
        var roots = activeSourceRoots.get();
        if (roots == null || roots.isEmpty()) {
            return true;
        }
        for (var root : roots) {
            if (file.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static final Logger LOG = Logger.getLogger("main");

    static synchronized long workspaceVersion() {
        return workspaceVersion;
    }

    private static void initCache(Set<Path> roots) {
        var workspaceId = workspaceId(roots);
        var dir = Paths.get(System.getProperty("user.home"), ".cache", "jls", workspaceId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warning("Failed to create cache dir: " + e.getMessage());
            return;
        }
        cacheFile = dir.resolve("java-sources.json");
        indexCacheFile = dir.resolve("workspace-index.json");
        WorkspaceIndex.setCacheFile(indexCacheFile);
        CacheConfig.setCacheDirIfUnset(dir);
        LOG.info("Workspace cache file: " + cacheFile);
        LOG.info("Workspace index cache file: " + indexCacheFile);
    }

    private static int loadCache() {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            return 0;
        }
        try (var reader = Files.newBufferedReader(cacheFile)) {
            @SuppressWarnings("unchecked")
            List<CacheEntry> list =
                    GSON.fromJson(reader, new TypeToken<List<CacheEntry>>() {}.getType());
            if (list == null) return 0;
            int loaded = 0;
            synchronized (FileStore.class) {
                javaSources.clear();
                for (var entry : list) {
                    if (entry == null || entry.path == null) continue;
                    var path = Paths.get(entry.path);
                    try {
                        if (!Files.isRegularFile(path)) continue;
                        var modified = Files.getLastModifiedTime(path).toInstant();
                        if (modified.toEpochMilli() != entry.modifiedEpochMillis) continue;
                        javaSources.put(path, new Info(modified, entry.packageName == null ? "" : entry.packageName));
                        loaded++;
                    } catch (IOException e) {
                        // ignore invalid entries
                    }
                }
            }
            return loaded;
        } catch (IOException e) {
            LOG.warning("Failed to read java sources cache: " + e.getMessage());
            return 0;
        } catch (RuntimeException e) {
            LOG.warning("Failed to parse java sources cache: " + e.getMessage());
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException deleteError) {
                LOG.warning("Failed to delete corrupted java sources cache: " + deleteError.getMessage());
            }
            return 0;
        }
    }

    private static synchronized void scheduleCacheSave() {
        if (cacheFile == null || cacheWriteScheduled) return;
        cacheWriteScheduled = true;
        CACHE_WRITER.schedule(
                () -> {
                    try {
                        writeCache();
                    } finally {
                        synchronized (FileStore.class) {
                            cacheWriteScheduled = false;
                        }
                    }
                },
                CACHE_WRITE_DELAY_MS,
                TimeUnit.MILLISECONDS);
    }

    private static void writeCache() {
        if (cacheFile == null) return;
        List<CacheEntry> list;
        synchronized (FileStore.class) {
            list = new ArrayList<>(javaSources.size());
            for (var entry : javaSources.entrySet()) {
                var info = entry.getValue();
                list.add(new CacheEntry(entry.getKey().toString(), info.modified.toEpochMilli(), info.packageName));
            }
        }
        try (var writer = Files.newBufferedWriter(cacheFile)) {
            GSON.toJson(list, writer);
        } catch (IOException e) {
            LOG.warning("Failed to write java sources cache: " + e.getMessage());
        }
        WorkspaceIndex.saveCache();
    }

    private static void scheduleBackgroundIndex(List<Path> files) {
        if (files == null || files.isEmpty()) return;
        BACKGROUND_INDEXER.execute(() -> indexFilesInParallel(files));
    }

    private static void indexFilesInParallel(List<Path> files) {
        if (files == null || files.isEmpty()) return;
        var started = System.nanoTime();
        files.parallelStream().forEach(FileStore::indexFileFromDisk);
        LOG.fine(
                String.format(
                        "Indexed %,d java files in %,d ms",
                        files.size(),
                        (System.nanoTime() - started) / 1_000_000));
    }

    private static void indexFileFromDisk(Path file) {
        try {
            var modified = Files.getLastModifiedTime(file).toInstant();
            var modifiedMillis = modified.toEpochMilli();
            Info info;
            synchronized (FileStore.class) {
                info = javaSources.get(file);
            }
            var infoCached = info != null && info.modified.toEpochMilli() == modifiedMillis;
            if (infoCached && WorkspaceIndex.isCached(file, modifiedMillis)) {
                return;
            }
            var contents = Files.readString(file);
            var packageName = infoCached ? info.packageName : StringSearch.packageName(contents);
            var tokens = WorkspaceIndex.tokenizeContents(contents);
            if (!infoCached) {
                synchronized (FileStore.class) {
                    javaSources.put(file, new Info(modified, packageName));
                    workspaceVersion++;
                }
            }
            WorkspaceIndex.updateFileTokens(file, tokens, modifiedMillis);
        } catch (NoSuchFileException | CharacterCodingException e) {
            LOG.warning(e.getMessage());
            synchronized (FileStore.class) {
                javaSources.remove(file);
                workspaceVersion++;
            }
            WorkspaceIndex.removeFile(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String workspaceId(Set<Path> roots) {
        try {
            var list = new ArrayList<String>();
            for (var r : roots) {
                list.add(r.toAbsolutePath().normalize().toString());
            }
            Collections.sort(list);
            if (list.isEmpty()) {
                return "workspace";
            }
            var primary = Paths.get(list.get(0));
            var name = primary.getFileName();
            if (name == null) {
                return "workspace";
            }
            return name.toString();
        } catch (RuntimeException e) {
            return "workspace";
        }
    }

    private static class CacheEntry {
        final String path;
        final long modifiedEpochMillis;
        final String packageName;

        CacheEntry(String path, long modifiedEpochMillis, String packageName) {
            this.path = path;
            this.modifiedEpochMillis = modifiedEpochMillis;
            this.packageName = packageName;
        }
    }
}

class VersionedContent {
    final String content;
    final int version;
    final Instant modified = Instant.now();

    VersionedContent(String content, int version) {
        Objects.requireNonNull(content, "content is null");
        this.content = content;
        this.version = version;
    }
}
