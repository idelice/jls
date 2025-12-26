package org.javacs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.tools.*;
import org.javacs.index.WorkspaceIndex;

class JavaCompilerService implements CompilerProvider {
    // Not modifiable! If you want to edit these, you need to create a new instance
    final Set<Path> classPath, docPath;
    final Set<String> addExports;
    final ReusableCompiler compiler = new ReusableCompiler(LombokSupport.isEnabled());
    final Docs docs;
    final Set<String> jdkClasses = ScanClassPath.jdkTopLevelClasses(), classPathClasses;
    // Diagnostics from the last compilation task
    final List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
    // Use the same file manager for multiple tasks, so we don't repeatedly re-compile the same files
    // TODO intercept files that aren't in the batch and erase method bodies so compilation is faster
    final SourceFileManager fileManager;

    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports) {
        var cp = new LinkedHashSet<Path>();
        var lombokPath = LombokSupport.lombokPath();
        if (lombokPath != null) {
            cp.add(Paths.get(lombokPath));
        }
        cp.addAll(classPath);
        // classPath can't actually be modified, because JavaCompiler remembers it from task to task
        this.classPath = Collections.unmodifiableSet(cp);
        this.docPath = Collections.unmodifiableSet(docPath);
        this.addExports = Collections.unmodifiableSet(addExports);
        this.docs = new Docs(docPath);
        this.classPathClasses = ScanClassPath.classPathTopLevelClasses(classPath);
        this.fileManager = new SourceFileManager();
        try {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, cp);
            if (LombokSupport.isEnabled()) {
                fileManager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, cp);
            }
            var sourceRoots = FileStore.sourceRoots();
            if (!sourceRoots.isEmpty()) {
                fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourceRoots);
            }
            var classOutput = Files.createTempDirectory("jls-classes-");
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOutput));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private CompileBatch cachedCompile;
    private Map<JavaFileObject, Long> cachedModified = new HashMap<>();
    private long cachedReferenceVersion = -1;
    private final Map<String, Path[]> cachedTypeReferences = new HashMap<>();
    private final Map<String, Path[]> cachedMemberReferences = new HashMap<>();
    private long cachedLombokVersion = -1;
    private final List<Path> cachedLombokFiles = new ArrayList<>();

    private boolean needsCompile(Collection<? extends JavaFileObject> sources) {
        if (cachedModified.size() != sources.size()) {
            return true;
        }
        for (var f : sources) {
            if (!cachedModified.containsKey(f)) {
                return true;
            }
            if (f.getLastModified() != cachedModified.get(f)) {
                return true;
            }
        }
        return false;
    }

    private void loadCompile(Collection<? extends JavaFileObject> sources) {
        if (cachedCompile != null) {
            if (!cachedCompile.closed) {
                throw new RuntimeException("Compiler is still in-use!");
            }
            cachedCompile.borrow.close();
        }
        cachedCompile = null;
        cachedCompile = doCompile(sources);
        clearCachedModified();
        for (var f : sources) {
            cachedModified.put(f, f.getLastModified());
        }
    }

    private CompileBatch doCompile(Collection<? extends JavaFileObject> sources) {
        if (sources.isEmpty()) throw new RuntimeException("empty sources");
        var firstAttempt = new CompileBatch(this, sources);
        Set<Path> addFiles;
        try {
            addFiles = firstAttempt.needsAdditionalSources();
        } catch (RuntimeException e) {
            firstAttempt.close();
            firstAttempt.borrow.close();
            throw e;
        }
        if (addFiles.isEmpty()) return firstAttempt;
        // If the compiler needs additional source files that contain package-private files
        LOG.info("...need to recompile with " + addFiles);
        firstAttempt.close();
        firstAttempt.borrow.close();
        var moreSources = new ArrayList<JavaFileObject>();
        moreSources.addAll(sources);
        for (var add : addFiles) {
            moreSources.add(new SourceFileObject(add, false));
        }
        return new CompileBatch(this, deduplicateSources(moreSources));
    }

    private CompileBatch compileBatch(Collection<? extends JavaFileObject> sources) {
        var uniqueSources = deduplicateSources(sources);
        if (needsCompile(uniqueSources)) {
            loadCompile(uniqueSources);
        } else {
            LOG.info("...using cached compile");
        }
        return cachedCompile;
    }

    private List<JavaFileObject> deduplicateSources(Collection<? extends JavaFileObject> sources) {
        if (sources.isEmpty()) {
            return List.of();
        }
        var seen = new LinkedHashMap<java.net.URI, JavaFileObject>();
        for (var source : sources) {
            seen.put(source.toUri(), source);
        }
        if (seen.size() == sources.size() && sources instanceof List) {
            @SuppressWarnings("unchecked")
            var list = (List<JavaFileObject>) sources;
            return list;
        }
        return new ArrayList<>(seen.values());
    }

    private static final Pattern PACKAGE_EXTRACTOR = Pattern.compile("^([a-z][_a-zA-Z0-9]*\\.)*[a-z][_a-zA-Z0-9]*");

    private String packageName(String className) {
        var m = PACKAGE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    private static final Pattern SIMPLE_EXTRACTOR = Pattern.compile("[A-Z][_a-zA-Z0-9]*$");

    private String simpleName(String className) {
        var m = SIMPLE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    private static final Cache<String, Boolean> cacheContainsWord = new Cache<>();

    private boolean containsWord(Path file, String word) {
        if (cacheContainsWord.needs(file, word)) {
            cacheContainsWord.load(file, word, StringSearch.containsWord(file, word));
        }
        return cacheContainsWord.get(file, word);
    }

    private static final Cache<Void, List<String>> cacheContainsType = new Cache<>();

    private boolean containsType(Path file, String className) {
        if (cacheContainsType.needs(file, null)) {
            var root = parse(file).root;
            var types = new ArrayList<String>();
            new FindTypeDeclarations().scan(root, types);
            cacheContainsType.load(file, null, types);
        }
        return cacheContainsType.get(file, null).contains(className);
    }

    private Cache<Void, List<String>> cacheFileImports = new Cache<>();

    private List<String> readImports(Path file) {
        if (cacheFileImports.needs(file, null)) {
            loadImports(file);
        }
        return cacheFileImports.get(file, null);
    }

    private void loadImports(Path file) {
        var list = new ArrayList<String>();
        var importClass = Pattern.compile("^import +([\\w\\.]+\\.\\w+);");
        var importStar = Pattern.compile("^import +([\\w\\.]+\\.\\*);");
        try (var lines = FileStore.lines(file)) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                // If we reach a class declaration, stop looking for imports
                // TODO This could be a little more specific
                if (line.contains("class")) break;
                // import foo.bar.Doh;
                var matchesClass = importClass.matcher(line);
                if (matchesClass.matches()) {
                    list.add(matchesClass.group(1));
                }
                // import foo.bar.*
                var matchesStar = importStar.matcher(line);
                if (matchesStar.matches()) {
                    list.add(matchesStar.group(1));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        cacheFileImports.load(file, null, list);
    }

    @Override
    public Set<String> imports() {
        var all = new HashSet<String>();
        for (var f : FileStore.all()) {
            all.addAll(readImports(f));
        }
        return all;
    }

    @Override
    public List<String> publicTopLevelTypes() {
        var all = new ArrayList<String>();
        for (var file : FileStore.all()) {
            var fileName = file.getFileName().toString();
            if (!fileName.endsWith(".java")) continue;
            var className = fileName.substring(0, fileName.length() - ".java".length());
            var packageName = FileStore.packageName(file);
            if (packageName != null && !packageName.isEmpty()) {
                className = packageName + "." + className;
            }
            all.add(className);
        }
        all.addAll(classPathClasses);
        all.addAll(jdkClasses);
        return all;
    }

    @Override
    public List<String> packagePrivateTopLevelTypes(String packageName) {
        return List.of("TODO");
    }

    private boolean containsImport(Path file, String className) {
        var packageName = packageName(className);
        // Note: FileStore.packageName may return null.
        if (packageName.equals(FileStore.packageName(file))) return true;
        var star = packageName + ".*";
        for (var i : readImports(file)) {
            if (i.equals(className) || i.equals(star)) return true;
        }
        return false;
    }

    @Override
    public Iterable<Path> search(String query) {
        Predicate<Path> test = f -> StringSearch.containsWordMatching(f, query);
        return () -> FileStore.all().stream().filter(test).iterator();
    }

    @Override
    public Optional<JavaFileObject> findAnywhere(String className) {
        var fromDocs = findPublicTypeDeclarationInDocPath(className);
        if (fromDocs.isPresent()) {
            return fromDocs;
        }
        var fromJdk = findPublicTypeDeclarationInJdk(className);
        if (fromJdk.isPresent()) {
            return fromJdk;
        }
        var fromSource = findTypeDeclaration(className);
        if (fromSource != NOT_FOUND) {
            return Optional.of(new SourceFileObject(fromSource));
        }
        return Optional.empty();
    }

    private Optional<JavaFileObject> findPublicTypeDeclarationInDocPath(String className) {
        try {
            var found =
                    docs.fileManager.getJavaFileForInput(
                            StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
            return Optional.ofNullable(found);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<JavaFileObject> findPublicTypeDeclarationInJdk(String className) {
        try {
            for (var module : ScanClassPath.JDK_MODULES) {
                var moduleLocation = docs.fileManager.getLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module);
                if (moduleLocation == null) continue;
                var fromModuleSourcePath =
                        docs.fileManager.getJavaFileForInput(moduleLocation, className, JavaFileObject.Kind.SOURCE);
                if (fromModuleSourcePath != null) {
                    LOG.info(String.format("...found %s in module %s of jdk", fromModuleSourcePath.toUri(), module));
                    return Optional.of(fromModuleSourcePath);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Optional.empty();
    }

    @Override
    public Path findTypeDeclaration(String className) {
        var fastFind = findPublicTypeDeclaration(className);
        if (fastFind != NOT_FOUND) return fastFind;
        // In principle, the slow path can be skipped in many cases.
        // If we're spending a lot of time in findTypeDeclaration, this would be a good optimization.
        var packageName = packageName(className);
        var simpleName = simpleName(className);
        for (var f : FileStore.list(packageName)) {
            if (containsWord(f, simpleName) && containsType(f, className)) {
                return f;
            }
        }
        return NOT_FOUND;
    }

    private Path findPublicTypeDeclaration(String className) {
        JavaFileObject source;
        try {
            source =
                    fileManager.getJavaFileForInput(
                            StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (source == null) return NOT_FOUND;
        if (!source.toUri().getScheme().equals("file")) return NOT_FOUND;
        var file = Paths.get(source.toUri());
        if (!containsType(file, className)) return NOT_FOUND;
        return file;
    }

    @Override
    public Path[] findTypeReferences(String className) {
        refreshReferenceCacheIfNeeded();
        var cached = cachedTypeReferences.get(className);
        if (cached != null) {
            return cached;
        }
        var started = System.nanoTime();
        var simpleName = simpleName(className);
        var candidates = new ArrayList<Path>();
        for (var f : WorkspaceIndex.filesContaining(simpleName)) {
            if (containsImport(f, className)) {
                candidates.add(f);
            }
        }
        var result = candidates.toArray(Path[]::new);
        cachedTypeReferences.put(className, result);
        LOG.fine(
                String.format(
                        "Type refs candidates for %s: %d files in %d ms (workspace files=%d)",
                        className,
                        result.length,
                        (System.nanoTime() - started) / 1_000_000,
                        FileStore.all().size()));
        return result;
    }

    @Override
    public Path[] findMemberReferences(String className, String memberName) {
        refreshReferenceCacheIfNeeded();
        var key = className + "#" + memberName;
        var cached = cachedMemberReferences.get(key);
        if (cached != null) {
            return cached;
        }
        var started = System.nanoTime();
        var candidates = new ArrayList<Path>(WorkspaceIndex.filesContaining(memberName));
        var result = candidates.toArray(Path[]::new);
        cachedMemberReferences.put(key, result);
        LOG.fine(
                String.format(
                        "Member refs candidates for %s#%s: %d files in %d ms (workspace files=%d)",
                        className,
                        memberName,
                        result.length,
                        (System.nanoTime() - started) / 1_000_000,
                        FileStore.all().size()));
        return result;
    }

    @Override
    public ParseTask parse(Path file) {
        var parser = Parser.parseFile(file);
        return new ParseTask(parser.task, parser.root);
    }

    @Override
    public ParseTask parse(JavaFileObject file) {
        var parser = Parser.parseJavaFileObject(file);
        return new ParseTask(parser.task, parser.root);
    }

    @Override
    public CompileTask compile(Path... files) {
        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            sources.add(new SourceFileObject(f));
        }
        return compile(sources);
    }

    void clearCachedModified() {
        cachedModified.clear();
    }

    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        var activeRoots = selectActiveSourceRoots(sources);
        var started = System.nanoTime();
        if (!activeRoots.isEmpty()) {
            FileStore.setActiveSourceRoots(activeRoots);
        }
        try {
            var expanded = maybeExpandSourcesForLombok(sources);
            var compile = compileBatch(expanded);
            return new CompileTask(compile.task, compile.roots, diags, compile::close);
        } finally {
            FileStore.clearActiveSourceRoots();
            if (!activeRoots.isEmpty()) {
                LOG.fine(
                        String.format(
                                "Compile source roots: active=%d total=%d in %d ms",
                                activeRoots.size(),
                                FileStore.sourceRoots().size(),
                                (System.nanoTime() - started) / 1_000_000));
            }
        }
    }

    private Set<Path> selectActiveSourceRoots(Collection<? extends JavaFileObject> sources) {
        var allRoots = FileStore.sourceRoots();
        if (allRoots.isEmpty()) {
            return allRoots;
        }
        for (var source : sources) {
            var path = sourcePath(source);
            if (path != null && isTestPath(path)) {
                return allRoots;
            }
        }
        var testRoots = new HashSet<Path>();
        for (var root : allRoots) {
            if (isTestRoot(root)) {
                testRoots.add(root);
            }
        }
        if (testRoots.isEmpty()) {
            return allRoots;
        }
        var mainRoots = new LinkedHashSet<Path>(allRoots);
        mainRoots.removeAll(testRoots);
        if (mainRoots.isEmpty()) {
            return allRoots;
        }
        for (var source : sources) {
            var path = sourcePath(source);
            if (path != null && isInRoots(path, testRoots)) {
                return allRoots;
            }
        }
        return mainRoots;
    }

    private boolean isTestRoot(Path root) {
        var normalized = root.toString().replace('\\', '/');
        return normalized.contains("/src/test/")
                || normalized.endsWith("/src/test/java")
                || normalized.endsWith("/src/test");
    }

    private boolean isTestPath(Path path) {
        var normalized = path.toString().replace('\\', '/');
        return normalized.contains("/src/test/") || normalized.contains("/src/test/java/");
    }

    private boolean isInRoots(Path path, Set<Path> roots) {
        for (var root : roots) {
            if (path.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private Path sourcePath(JavaFileObject source) {
        if (source instanceof SourceFileObject) {
            return ((SourceFileObject) source).path;
        }
        try {
            var uri = source.toUri();
            if (uri != null && "file".equals(uri.getScheme())) {
                return Paths.get(uri);
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private Collection<? extends JavaFileObject> maybeExpandSourcesForLombok(
            Collection<? extends JavaFileObject> sources) {
        if (!LombokSupport.isEnabled()) {
            return sources;
        }
        var started = System.nanoTime();
        var lombokFiles = lombokAnnotatedFiles();
        if (lombokFiles.isEmpty()) {
            return sources;
        }
        // Lombok only needs annotated sources in the same compilation round to generate members.
        // Include all Lombok-annotated files plus the requested sources.
        var all = new ArrayList<JavaFileObject>(sources.size() + lombokFiles.size());
        all.addAll(sources);
        for (var f : lombokFiles) {
            all.add(new SourceFileObject(f, false));
        }
        LOG.fine(
                String.format(
                        "Lombok expansion: sources=%d lombokFiles=%d total=%d",
                        sources.size(),
                        lombokFiles.size(),
                        all.size()));
        LOG.fine(
                String.format(
                        "Lombok expansion computed in %d ms",
                        (System.nanoTime() - started) / 1_000_000));
        return deduplicateSources(all);
    }

    private static final Logger LOG = Logger.getLogger("main");

    private void refreshReferenceCacheIfNeeded() {
        var version = FileStore.workspaceVersion();
        if (version != cachedReferenceVersion) {
            cachedReferenceVersion = version;
            cachedTypeReferences.clear();
            cachedMemberReferences.clear();
        }
    }

    private List<Path> lombokAnnotatedFiles() {
        var version = FileStore.workspaceVersion();
        if (version == cachedLombokVersion) {
            return cachedLombokFiles;
        }
        cachedLombokFiles.clear();
        for (var f : FileStore.all()) {
            if (containsWord(f, "lombok")) {
                cachedLombokFiles.add(f);
            }
        }
        LOG.fine(String.format("Detected %d Lombok-annotated files in workspace", cachedLombokFiles.size()));
        cachedLombokVersion = version;
        return cachedLombokFiles;
    }
}
