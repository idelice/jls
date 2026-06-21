package org.javacs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.*;
import org.javacs.completion.ExternalBinaryDecompiler;

class JavaCompilerService implements CompilerProvider {
    private static final Logger LOG = Logger.getLogger("main");

    // classpath is effectively immutable per compiler instance.
    // Upgrade: addClassPathEntries() for multi-module support.
    volatile Set<Path> classPath;
    final Set<Path> docPath;
    final Set<String> addExports;
    final List<String> extraArgs;
    final ReusableCompiler compiler = new ReusableCompiler();
    final Set<String> jdkClasses, classPathClasses;
    final boolean lombokPresentOnClasspath;
    final List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
    final SourceFileManager fileManager;
    final SourceFileManager docsFileManager;

    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports, Collection<String> extraArgs) {
        this.classPath = Collections.unmodifiableSet(classPath);
        this.docPath = Collections.unmodifiableSet(docPath);
        this.addExports = Collections.unmodifiableSet(addExports);
        this.extraArgs = List.copyOf(extraArgs);
        this.jdkClasses = ScanClassPath.jdkTopLevelClasses();
        this.classPathClasses = ScanClassPath.classPathTopLevelClasses(classPath);
        this.lombokPresentOnClasspath = classPath.stream().anyMatch(p -> {
            var name = p.getFileName().toString().toLowerCase();
            return name.startsWith("lombok") && (name.endsWith(".jar") || name.endsWith("-all.jar"));
        }) && workspaceUsesLombok();
        this.fileManager = new SourceFileManager();
        this.docsFileManager = new Docs(docPath).createFileManager();
    }

    // Convenience constructor for tests
    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports, Set<String> extraArgs) {
        this(classPath, docPath, addExports, (Collection<String>) extraArgs);
    }

    private static boolean workspaceUsesLombok() {
        for (var file : FileStore.all()) {
            // Skip test fixtures/resources/examples (not actual project source)
            var path = file.toString();
            // TODO: jls.test is temporary — needs proper multi-workspace support
            // to separate test fixtures from main workspace without breaking Lombok detection
            if (System.getProperty("jls.test") == null
                    && (path.contains("/test/resources/") || path.contains("/test/examples/")
                    || path.contains("/test-resources/"))) continue;
            try (var reader = FileStore.lines(file)) {
                for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.startsWith("import lombok")) return true;
                    if (line.contains("class ") || line.contains("interface ") || line.contains("enum ")) break;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    boolean isBuildOutputAvailable() {
        for (var path : classPath) {
            if (Files.isDirectory(path) && !path.getFileName().toString().endsWith(".jar")) {
                try (var entries = java.nio.file.Files.list(path)) {
                    if (entries.anyMatch(Files::isDirectory)) {
                        return true;
                    }
                } catch (IOException ignored) {}
            }
        }
        return false;
    }

    /** Atomically extend the classpath with new entries (e.g. compiled module output dirs). */
    void addClassPathEntries(Set<Path> entries) {
        if (entries.isEmpty()) return;
        var updated = new LinkedHashSet<>(this.classPath);
        if (updated.addAll(entries)) {
            this.classPath = Collections.unmodifiableSet(updated);
            LOG.info(String.format("[compiler] classpath_extended added=%d total=%d", entries.size(), updated.size()));
        }
    }

    // --- Single-cache model ---
    // Invalidated by FileStore.contentRevision() which bumps on every didChange/didSave.
    private CompileBatch cachedCompile;
    private long cachedRevision = -1;

    private boolean needsCompile() {
        if (cachedCompile == null) return true;
        return FileStore.contentRevision() != cachedRevision;
    }

    private void loadCompile(Collection<? extends JavaFileObject> sources) {
        cachedCompile = doCompile(sources);
        cachedRevision = FileStore.contentRevision();
    }

    private CompileBatch doCompile(Collection<? extends JavaFileObject> sources) {
        if (sources.isEmpty()) throw new RuntimeException("empty sources");

        var firstAttempt = new CompileBatch(this, sources);

        Set<Path> addFiles;
        try {
            addFiles = firstAttempt.needsAdditionalSources();
        } catch (RuntimeException e) {
            firstAttempt.close();
            throw e;
        }

        if (addFiles.isEmpty()) return firstAttempt;

        LOG.info("...need to recompile with " + addFiles);
        firstAttempt.close();

        var moreSources = new ArrayList<JavaFileObject>(sources);
        for (var add : addFiles) {
            moreSources.add(new SourceFileObject(add));
        }
        return new CompileBatch(this, moreSources);
    }

    private CompileBatch compileBatch(Collection<? extends JavaFileObject> sources) {
        var needsFresh = needsCompile() || (isBuildOutputAvailable() && lombokPresentOnClasspath);
        if (!needsFresh && cachedCompile != null) {
            // Verify cache covers requested sources — cached roots may be from a different file
            var cachedUris = new HashSet<URI>();
            for (var r : cachedCompile.roots) cachedUris.add(r.getSourceFile().toUri());
            for (var s : sources) {
                if (!cachedUris.contains(s.toUri())) {
                    needsFresh = true;
                    break;
                }
            }
        }
        if (needsFresh) {
            loadCompile(sources);
        } else {
            LOG.fine("...using cached compile");
        }
        return cachedCompile;
    }

    @Override
    public CompileTask compile(Path... files) {
        var sources = new ArrayList<JavaFileObject>(files.length);
        for (var f : files) sources.add(new SourceFileObject(f));
        return compile(sources);
    }

    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        Collection<? extends JavaFileObject> effectiveSources;
        if (lombokPresentOnClasspath && sources.size() <= 1) {
            if (isBuildOutputAvailable()) {
                // Include dirty documents so cross-file errors from edited files are visible
                var allSources = new LinkedHashSet<JavaFileObject>(sources);
                LOG.fine("[dirty] compile() has " + FileStore.dirtyDocuments().size() + " dirty documents");
                for (var dirty : FileStore.dirtyDocuments()) {
                    allSources.add(new SourceFileObject(dirty));
                }
                effectiveSources = allSources;
                LOG.fine("[compile] fast-path enabled: types from build output, compiling " + allSources.size() + " file(s)");
            } else {
                effectiveSources = FileStore.all().stream()
                        .<JavaFileObject>map(SourceFileObject::new)
                        .toList();
                LOG.fine("[compile] fallback: build output unavailable, compiling all " + effectiveSources.size() + " files");
            }
        } else {
            effectiveSources = sources;
        }
        var batch = compileBatch(effectiveSources);
        return new CompileTask(batch.task, batch.trees, batch.elements, batch.types, batch.roots, diags, batch::close);
    }

    @Override
    public ParseTask parse(Path file) {
        var parser = Parser.parseJavaFileObject(new SourceFileObject(file));
        return new ParseTask(parser.task, parser.root);
    }

    @Override
    public ParseTask parse(JavaFileObject file) {
        var parser = Parser.parseJavaFileObject(file);
        return new ParseTask(parser.task, parser.root);
    }

    // --- Type/symbol lookups ---

    private static final Pattern PACKAGE_EXTRACTOR = Pattern.compile("^([a-z][_a-zA-Z0-9]*\\.)*[a-z][_a-zA-Z0-9]*");
    private static final Pattern SIMPLE_EXTRACTOR = Pattern.compile("[A-Z][_a-zA-Z0-9]*$");
    private static final Pattern IMPORT_CLASS = Pattern.compile("^import +(static +)?([\\w\\.]+\\.\\w+);");
    private static final Pattern IMPORT_STAR = Pattern.compile("^import +(static +)?([\\w\\.]+\\.\\*);");

    private String packageName(String className) {
        var m = PACKAGE_EXTRACTOR.matcher(className);
        return m.find() ? m.group() : "";
    }

    private String simpleName(String className) {
        var m = SIMPLE_EXTRACTOR.matcher(className);
        return m.find() ? m.group() : "";
    }

    private static final Cache<String, Boolean> cacheContainsWord = new Cache<>("helper.contains_word");

    private boolean containsWord(Path file, String word) {
        if (cacheContainsWord.needs(file, word)) {
            cacheContainsWord.load(file, word, StringSearch.containsWord(file, word));
        }
        return cacheContainsWord.get(file, word);
    }

    private static final Cache<Void, List<String>> cacheContainsType = new Cache<>("helper.contains_type");

    private boolean containsType(Path file, String className) {
        if (cacheContainsType.needs(file, null)) {
            var root = parse(file).root();
            var types = new ArrayList<String>();
            new FindTypeDeclarations().scan(root, types);
            cacheContainsType.load(file, null, types);
        }
        return cacheContainsType.get(file, null).contains(className);
    }

    private final Cache<Void, List<String>> cacheFileImports = new Cache<>("helper.file_imports");

    private List<String> readImports(Path file) {
        if (cacheFileImports.needs(file, null)) {
            loadImports(file);
        }
        return cacheFileImports.get(file, null);
    }

    private static final Pattern CLASS_DECLARATION_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\b");

    private void loadImports(Path file) {
        var list = new ArrayList<String>();
        try (var lines = FileStore.lines(file)) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                if (!line.startsWith("import ") && !line.startsWith("package ")
                        && CLASS_DECLARATION_PATTERN.matcher(line).find()) break;
                var matchesClass = IMPORT_CLASS.matcher(line);
                if (matchesClass.matches()) {
                    list.add(matchesClass.group(2));
                }
                var matchesStar = IMPORT_STAR.matcher(line);
                if (matchesStar.matches()) {
                    list.add(matchesStar.group(2));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        cacheFileImports.load(file, null, list);
    }

    private boolean containsImport(Path file, String className) {
        var pkg = packageName(className);
        if (pkg.equals(FileStore.packageName(file))) return true;
        var packageStar = pkg + ".*";
        var staticStar = className + ".*";
        var staticMemberPrefix = className + ".";
        for (var i : readImports(file)) {
            if (i.equals(className) || i.equals(packageStar) || i.equals(staticStar) || i.startsWith(staticMemberPrefix))
                return true;
        }
        return false;
    }

    // --- CompilerProvider interface ---

    @Override
    public boolean lombokPresentOnClasspath() {
        return lombokPresentOnClasspath;
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
    public Set<Path> classPathRoots() {
        return classPath;
    }

    @Override
    public List<String> packagePrivateTopLevelTypes(String packageName) {
        return List.of("TODO");
    }

    @Override
    public Iterable<Path> search(String query) {
        Predicate<Path> test = f -> StringSearch.containsWordMatching(f, query);
        return () -> FileStore.all().stream().filter(test).iterator();
    }

    @Override
    public Optional<JavaFileObject> findAnywhere(String className) {
        var fromDocs = findPublicTypeDeclarationInDocPath(className);
        if (fromDocs.isPresent()) return fromDocs;
        var fromJdk = findPublicTypeDeclarationInJdk(className);
        if (fromJdk.isPresent()) return fromJdk;
        var fromSource = findTypeDeclaration(className);
        if (fromSource != NOT_FOUND) return Optional.of(new SourceFileObject(fromSource));
        return Optional.empty();
    }

    private Optional<JavaFileObject> findPublicTypeDeclarationInDocPath(String className) {
        try {
            var found = docsFileManager.getJavaFileForInput(
                    StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
            return Optional.ofNullable(found);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final Map<String, Optional<JavaFileObject>> jdkSourceCache = new ConcurrentHashMap<>();

    private Optional<JavaFileObject> findPublicTypeDeclarationInJdk(String className) {
        var cached = jdkSourceCache.get(className);
        if (cached != null) return cached;
        try {
            for (var module : ScanClassPath.JDK_MODULES) {
                var moduleLocation = docsFileManager.getLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module);
                if (moduleLocation == null) continue;
                var found = docsFileManager.getJavaFileForInput(moduleLocation, className, JavaFileObject.Kind.SOURCE);
                if (found != null) {
                    var result = Optional.of(found);
                    jdkSourceCache.put(className, result);
                    return result;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var notFound = Optional.<JavaFileObject>empty();
        jdkSourceCache.put(className, notFound);
        return notFound;
    }

    @Override
    public Path findTypeDeclaration(String className) {
        var fastFind = findPublicTypeDeclaration(className);
        if (fastFind != NOT_FOUND) return fastFind;
        var pkg = packageName(className);
        var simple = simpleName(className);
        for (var f : FileStore.list(pkg)) {
            if (containsWord(f, simple) && containsType(f, className)) {
                return f;
            }
        }
        return NOT_FOUND;
    }

    private Path findPublicTypeDeclaration(String className) {
        JavaFileObject source;
        try {
            source = fileManager.getJavaFileForInput(
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
        var pkg = packageName(className);
        var simple = simpleName(className);
        var candidates = new ArrayList<Path>();
        for (var f : FileStore.all()) {
            if (containsWord(f, pkg) && containsImport(f, className) && containsWord(f, simple)) {
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

    private volatile ExternalBinaryDecompiler decompiler;

    @Override
    public Optional<Path> decompileClass(String qualifiedName) {
        if (decompiler == null) {
            synchronized (this) {
                if (decompiler == null) {
                    var fingerprint = Integer.toHexString(
                            classPath.stream()
                                    .map(p -> p.toAbsolutePath().normalize().toString())
                                    .sorted()
                                    .collect(Collectors.joining("|"))
                                    .hashCode());
                    decompiler = new ExternalBinaryDecompiler(classPath, fingerprint, getClass().getClassLoader());
                }
            }
        }
        return decompiler.decompileSourcePath(qualifiedName);
    }
}
