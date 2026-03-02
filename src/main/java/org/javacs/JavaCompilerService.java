package org.javacs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.tools.*;

class JavaCompilerService implements CompilerProvider {
    // Not modifiable! If you want to edit these, you need to create a new instance
    final Set<Path> classPath, docPath;
    final Set<String> addExports;
    final Set<String> extraArgs;
    final ReusableCompiler compiler = new ReusableCompiler();
    final Docs docs;
    final Set<String> jdkClasses = ScanClassPath.jdkTopLevelClasses(), classPathClasses;
    final boolean lombokConfiguredEnabled;
    final boolean lombokPresentOnClasspath;
    private volatile boolean lombokApEnabled;
    // Diagnostics from the last compilation task
    final List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
    // Use the same file manager for multiple tasks, so we don't repeatedly re-compile the same files
    // TODO intercept files that aren't in the batch and erase method bodies so compilation is faster
    final SourceFileManager fileManager;

    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports, Set<String> extraArgs) {
        this(classPath, docPath, addExports, extraArgs, true);
    }

    JavaCompilerService(
            Set<Path> classPath,
            Set<Path> docPath,
            Set<String> addExports,
            Set<String> extraArgs,
            boolean lombokConfiguredEnabled) {
        System.err.println("Class path:");
        for (var p : classPath) {
            System.err.println("  " + p);
        }
        System.err.println("Doc path:");
        for (var p : docPath) {
            System.err.println("  " + p);
        }
        // classPath can't actually be modified, because JavaCompiler remembers it from task to task
        this.classPath = Collections.unmodifiableSet(classPath);
        this.docPath = Collections.unmodifiableSet(docPath);
        this.addExports = Collections.unmodifiableSet(addExports);
        this.extraArgs = Collections.unmodifiableSet(extraArgs);
        this.docs = new Docs(docPath);
        this.classPathClasses = ScanClassPath.classPathTopLevelClasses(classPath);
        this.lombokConfiguredEnabled = lombokConfiguredEnabled;
        this.lombokPresentOnClasspath = hasLombokJar(classPath);
        this.lombokApEnabled = this.lombokPresentOnClasspath && this.lombokConfiguredEnabled;
        LOG.info(
                String.format(
                        "[perf] lombok_config enabled=%s classpath_present=%s ap_enabled=%s",
                        this.lombokConfiguredEnabled, this.lombokPresentOnClasspath, this.lombokApEnabled));
        this.fileManager = new SourceFileManager();
    }

    private CompileBatch cachedCompile;
    private final Map<JavaFileObject, SourceFingerprint> cachedModified = new HashMap<>();
    private long cachedCompileContentRevision = -1;
    private CompileBatch cachedFastCompile;
    private final Map<JavaFileObject, SourceFingerprint> cachedFastModified = new HashMap<>();
    private long cachedFastCompileContentRevision = -1;
    private final Map<String, Optional<JavaFileObject>> jdkSourceCache = new ConcurrentHashMap<>();

    private static class SourceFingerprint {
        final long modifiedMillis;
        final int version;

        SourceFingerprint(long modifiedMillis, int version) {
            this.modifiedMillis = modifiedMillis;
            this.version = version;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SourceFingerprint)) {
                return false;
            }
            var that = (SourceFingerprint) other;
            return modifiedMillis == that.modifiedMillis && version == that.version;
        }

        @Override
        public int hashCode() {
            return Objects.hash(modifiedMillis, version);
        }
    }

    private SourceFingerprint fingerprint(JavaFileObject file) {
        var version = -1;
        if (file instanceof SourceFileObject sourceFileObject) {
            version = sourceFileObject.contentVersion();
        }
        return new SourceFingerprint(file.getLastModified(), version);
    }

    private boolean needsCompile(
            Collection<? extends JavaFileObject> sources, Map<JavaFileObject, SourceFingerprint> modifiedCache) {
        if (modifiedCache.size() != sources.size()) {
            return true;
        }
        for (var f : sources) {
            if (!modifiedCache.containsKey(f)) {
                return true;
            }
            var previous = modifiedCache.get(f);
            var current = fingerprint(f);
            if (!current.equals(previous)) {
                return true;
            }
        }
        return false;
    }

    private CompileBatch doCompile(
            Collection<? extends JavaFileObject> sources,
            CompileBatch.AnalysisMode mode,
            boolean allowAP) {
        if (sources.isEmpty()) throw new RuntimeException("empty sources");

        CompileBatch firstAttempt = null;
        try {
            firstAttempt = new CompileBatch(this, sources, allowAP, mode);
        } catch (CompileBatch.APFailureException e) {
            // AP failed - retry without AP if Lombok is present
            if (allowAP && lombokPresentOnClasspath) {
                disableLombokAnnotationProcessing("first-attempt", e);
                return doCompile(sources, mode, false);
            }
            throw e;
        } catch (RuntimeException e) {
            // Other compilation errors
            throw e;
        }

        if (mode == CompileBatch.AnalysisMode.ENTER_ONLY) {
            return firstAttempt;
        }

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
            moreSources.add(new SourceFileObject(add));
        }

        CompileBatch secondAttempt = null;
        try {
            secondAttempt = new CompileBatch(this, moreSources, allowAP, mode);
        } catch (CompileBatch.APFailureException e) {
            // AP failed on second attempt too - retry without AP
            if (allowAP && lombokPresentOnClasspath) {
                disableLombokAnnotationProcessing("second-attempt", e);
                return doCompile(moreSources, mode, false);
            }
            throw e;
        } catch (RuntimeException e) {
            // Other compilation errors
            throw e;
        }

        return secondAttempt;
    }

    private void loadCompile(
            Collection<? extends JavaFileObject> sources,
            CompileBatch.AnalysisMode mode,
            boolean allowAP,
            long contentRevision) {
        var compileCache = mode == CompileBatch.AnalysisMode.FULL ? cachedCompile : cachedFastCompile;
        var modifiedCache = mode == CompileBatch.AnalysisMode.FULL ? cachedModified : cachedFastModified;
        if (mode == CompileBatch.AnalysisMode.FULL) {
            closeCacheIfIdle(cachedFastCompile, cachedFastModified);
            cachedFastCompile = null;
            cachedFastCompileContentRevision = -1;
        } else {
            closeCacheIfIdle(cachedCompile, cachedModified);
            cachedCompile = null;
            cachedCompileContentRevision = -1;
        }
        if (compileCache != null) {
            if (!compileCache.closed) {
                throw new RuntimeException("Compiler is still in-use!");
            }
            compileCache.borrow.close();
        }
        var loaded = doCompile(sources, mode, allowAP);
        if (mode == CompileBatch.AnalysisMode.FULL) {
            cachedCompile = loaded;
            cachedCompileContentRevision = contentRevision;
        } else {
            cachedFastCompile = loaded;
            cachedFastCompileContentRevision = contentRevision;
        }
        modifiedCache.clear();
        for (var f : sources) {
            modifiedCache.put(f, fingerprint(f));
        }
    }

    private void closeCacheIfIdle(CompileBatch cache, Map<JavaFileObject, SourceFingerprint> modifiedCache) {
        if (cache == null) return;
        if (!cache.closed) {
            throw new RuntimeException("Compiler is still in-use!");
        }
        cache.borrow.close();
        modifiedCache.clear();
    }

    private CompileBatch compileBatch(
            Collection<? extends JavaFileObject> sources, CompileBatch.AnalysisMode mode, boolean allowAP) {
        boolean useAP = allowAP && lombokApEnabled;
        var effectiveSources = expandSourcesForLombokAP(sources, useAP);
        var modifiedCache = mode == CompileBatch.AnalysisMode.FULL ? cachedModified : cachedFastModified;
        var currentContentRevision = FileStore.contentRevision();
        var cachedContentRevision =
                mode == CompileBatch.AnalysisMode.FULL
                        ? cachedCompileContentRevision
                        : cachedFastCompileContentRevision;
        if (cachedContentRevision != currentContentRevision || needsCompile(effectiveSources, modifiedCache)) {
            if (cachedContentRevision != currentContentRevision) {
                LOG.info(
                        String.format(
                                "[perf] javac_cache_refresh mode=%s reason=content_revision_change cached=%d current=%d",
                                mode.name().toLowerCase(), cachedContentRevision, currentContentRevision));
            }
            loadCompile(effectiveSources, mode, useAP, currentContentRevision);
        } else {
            LOG.info(
                    String.format(
                            "[perf] javac_cache_hit mode=%s content_revision=%d",
                            mode.name().toLowerCase(), currentContentRevision));
        }
        return mode == CompileBatch.AnalysisMode.FULL ? cachedCompile : cachedFastCompile;
    }

    private Collection<? extends JavaFileObject> expandSourcesForLombokAP(
            Collection<? extends JavaFileObject> sources, boolean allowAP) {
        if (!allowAP || !lombokPresentOnClasspath) {
            return sources;
        }

        var expandedByPath = new LinkedHashMap<Path, JavaFileObject>();
        var expandedNonFile = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            var path = sourcePath(source);
            if (path != null) {
                expandedByPath.putIfAbsent(path, source);
            } else {
                expandedNonFile.add(source);
            }
        }

        var initialFileCount = expandedByPath.size();
        var roots = new ArrayList<>(expandedByPath.keySet());
        for (var root : roots) {
            addLombokSourcesFromPackage(expandedByPath, root, FileStore.packageName(root));
            for (var imported : readImports(root)) {
                if (imported.endsWith(".*")) {
                    addLombokSourcesFromPackage(expandedByPath, root, imported.substring(0, imported.length() - 2));
                    continue;
                }
                var declaration = findTypeDeclaration(imported);
                if (declaration == NOT_FOUND) continue;
                if (!sourceUsesLombok(declaration)) continue;
                expandedByPath.putIfAbsent(declaration, new SourceFileObject(declaration));
            }
        }

        if (expandedByPath.size() == initialFileCount) {
            return sources;
        }

        var expanded = new ArrayList<JavaFileObject>(expandedByPath.size() + expandedNonFile.size());
        expanded.addAll(expandedByPath.values());
        expanded.addAll(expandedNonFile);
        LOG.info(
                String.format(
                        "[perf] lombok_ap_sources requested=%d expanded=%d",
                        sources.size(), expanded.size()));
        return expanded;
    }

    private void addLombokSourcesFromPackage(
            Map<Path, JavaFileObject> expandedByPath, Path source, String packageName) {
        if (packageName == null) return;
        for (var candidate : FileStore.list(packageName)) {
            if (candidate.equals(source)) continue;
            if (!sourceUsesLombok(candidate)) continue;
            if (!containsWord(source, simpleTypeName(candidate))) continue;
            expandedByPath.putIfAbsent(candidate, new SourceFileObject(candidate));
        }
    }

    private String simpleTypeName(Path file) {
        var name = file.getFileName().toString();
        if (name.endsWith(".java")) {
            return name.substring(0, name.length() - ".java".length());
        }
        return name;
    }

    private Path sourcePath(JavaFileObject source) {
        if (source instanceof SourceFileObject) {
            return ((SourceFileObject) source).path;
        }
        var uri = source.toUri();
        if (uri == null || !"file".equals(uri.getScheme())) {
            return null;
        }
        try {
            return Paths.get(uri);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void disableLombokAnnotationProcessing(String phase, RuntimeException failure) {
        if (!lombokApEnabled) {
            return;
        }
        lombokApEnabled = false;
        dropCachedCompilation(cachedCompile, cachedModified);
        cachedCompile = null;
        cachedCompileContentRevision = -1;
        dropCachedCompilation(cachedFastCompile, cachedFastModified);
        cachedFastCompile = null;
        cachedFastCompileContentRevision = -1;
        var root = rootCause(failure);
        var message =
                String.format(
                        "[perf] lombok_ap disabled=true phase=%s reason=%s root=%s",
                        phase, failure.getMessage(), root.toString());
        LOG.log(Level.WARNING, message, failure);
    }

    private static Throwable rootCause(Throwable t) {
        var current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private void dropCachedCompilation(CompileBatch cached, Map<JavaFileObject, SourceFingerprint> modifiedCache) {
        if (cached != null && cached.closed) {
            cached.borrow.close();
        }
        modifiedCache.clear();
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
    private final Cache<Void, Boolean> cacheSourceUsesLombok = new Cache<>();

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

    private boolean sourceUsesLombok(Path file) {
        if (cacheSourceUsesLombok.needs(file, null)) {
            var usesLombok = false;
            try (var lines = FileStore.lines(file)) {
                for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                    var trimmed = line.trim();
                    if (trimmed.contains("lombok.")) {
                        usesLombok = true;
                        break;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            cacheSourceUsesLombok.load(file, null, usesLombok);
        }
        return cacheSourceUsesLombok.get(file, null);
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
        var cached = jdkSourceCache.get(className);
        if (cached != null) {
            LOG.info(String.format("[perf] jdk_lookup class=%s took=0ms cache=hit found=%s", className, cached.isPresent()));
            return cached;
        }
        var started = System.nanoTime();
        try {
            for (var module : ScanClassPath.JDK_MODULES) {
                var moduleLocation = docs.fileManager.getLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module);
                if (moduleLocation == null) continue;
                var fromModuleSourcePath =
                        docs.fileManager.getJavaFileForInput(moduleLocation, className, JavaFileObject.Kind.SOURCE);
                if (fromModuleSourcePath != null) {
                    LOG.info(String.format("...found %s in module %s of jdk", fromModuleSourcePath.toUri(), module));
                    var found = Optional.of(fromModuleSourcePath);
                    jdkSourceCache.put(className, found);
                    LOG.info(
                            String.format(
                                    "[perf] jdk_lookup class=%s took=%dms cache=miss found=true",
                                    className, (System.nanoTime() - started) / 1_000_000));
                    return found;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var notFound = Optional.<JavaFileObject>empty();
        jdkSourceCache.put(className, notFound);
        LOG.info(
                String.format(
                        "[perf] jdk_lookup class=%s took=%dms cache=miss found=false",
                        className, (System.nanoTime() - started) / 1_000_000));
        return notFound;
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
        var packageName = packageName(className);
        var simpleName = simpleName(className);
        var candidates = new ArrayList<Path>();
        for (var f : FileStore.all()) {
            if (containsWord(f, packageName) && containsImport(f, className) && containsWord(f, simpleName)) {
                candidates.add(f);
            }
        }
        return candidates.toArray(Path[]::new);
    }

    @Override
    public Path[] findMemberReferences(String className, String memberName) {
        var candidates = new ArrayList<Path>();
        for (var f : FileStore.all()) {
            if (containsWord(f, memberName)) {
                candidates.add(f);
            }
        }
        return candidates.toArray(Path[]::new);
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

    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        var compile = compileBatch(sources, CompileBatch.AnalysisMode.FULL, true);
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps, compile::close);
    }

    @Override
    public CompileTask compileFast(Path... files) {
        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            sources.add(new SourceFileObject(f));
        }
        return compileFast(sources);
    }

    @Override
    public CompileTask compileFastWithProcessors(Path... files) {
        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            sources.add(new SourceFileObject(f));
        }
        return compileFastWithProcessors(sources);
    }

    @Override
    public CompileTask compileFast(Collection<? extends JavaFileObject> sources) {
        var compile = compileBatch(sources, CompileBatch.AnalysisMode.ENTER_ONLY, false);
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps, compile::close);
    }

    @Override
    public CompileTask compileFastWithProcessors(Collection<? extends JavaFileObject> sources) {
        var compile = compileBatch(sources, CompileBatch.AnalysisMode.ENTER_ONLY, true);
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps, compile::close);
    }

    private static boolean hasLombokJar(Set<Path> classPath) {
        return classPath.stream()
                .anyMatch(
                        p -> {
                            var name = p.getFileName().toString().toLowerCase();
                            return name.startsWith("lombok") && (name.endsWith(".jar") || name.endsWith("-all.jar"));
                        });
    }

    private static final Logger LOG = Logger.getLogger("main");
}
