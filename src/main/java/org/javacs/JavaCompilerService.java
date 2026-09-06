package org.javacs;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.*;
import org.javacs.completion.ExternalBinaryDecompiler;

class JavaCompilerService implements CompilerProvider, AutoCloseable {
    private static final Logger LOG = Logger.getLogger("main");

    final Set<Path> classPath;
    final Set<Path> docPath;
    final Set<String> addExports;
    final List<String> extraArgs;
    final ReusableCompiler compiler = new ReusableCompiler();
    final Set<String> jdkClasses, classPathClasses;
    final boolean lombokPresentOnClasspath;
    SourceFileManager fileManager;
    private long sourceRevision = -1;
    final SourceFileManager docsFileManager;
    private Set<Path> sourceRoots = Set.of();

    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports, Collection<String> extraArgs) {
        this.classPath = Set.copyOf(classPath);
        this.docPath = Set.copyOf(docPath);
        this.addExports = Set.copyOf(addExports);
        this.extraArgs = List.copyOf(extraArgs);
        this.jdkClasses = ScanClassPath.jdkTopLevelClasses();
        this.classPathClasses = ScanClassPath.classPathTopLevelClasses(classPath);
        this.lombokPresentOnClasspath = lombokPresentOnClasspath(classPath);
        this.fileManager = new SourceFileManager(this::ownsSourceType);
        setSourceRoots(FileStore.sourceRoots());
        this.docsFileManager = new Docs(docPath).createFileManager();
    }

    // Convenience constructor for tests
    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports, Set<String> extraArgs) {
        this(classPath, docPath, addExports, (Collection<String>) extraArgs);
    }

    static boolean lombokPresentOnClasspath(Collection<Path> classPath) {
        return classPath.stream().anyMatch(path -> {
            var name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
            return name.startsWith("lombok") && (name.endsWith(".jar") || name.endsWith("-all.jar"));
        });
    }

    void setSourceRoots(Set<Path> sourceRoots) {
        this.sourceRoots = Set.copyOf(sourceRoots);
        configureSourcePath(this.sourceRoots);
        compiler.discard("source_roots_changed");
    }

    private void configureSourcePath(Collection<Path> sourceRoots) {
        try {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_PATH, sourceRoots);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void prepareFileManager() {
        if (sourceRevision == FileStore.sourceRevision()) return;
        compiler.discard("source_inventory_changed");
        try { fileManager.close(); } catch (IOException e) { LOG.fine(e.getMessage()); }
        fileManager = new SourceFileManager(this::ownsSourceType);
        configureSourcePath(sourceRoots);
        sourceRevision = FileStore.sourceRevision();
    }

    private boolean isSourceVisible(Path file) {
        return sourceRoots.isEmpty() || sourceRoots.stream().anyMatch(file::startsWith);
    }

    @Override
    public CompileTask compile(Path... files) {
        var sources = new ArrayList<JavaFileObject>(files.length);
        for (var f : files) sources.add(new SourceFileObject(f));
        return compile(sources);
    }

    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        return compile(sources, false);
    }

    @Override
    public CompileTask compileScan(Path... files) {
        var sources = new ArrayList<JavaFileObject>(files.length);
        for (var f : files) sources.add(new SourceFileObject(f));
        return compile(sources, true);
    }

    private CompileTask compile(Collection<? extends JavaFileObject> sources, boolean oneShot) {
        var batch = new CompileBatch(this, sources, oneShot);
        return new CompileTask(batch.task, batch.trees, batch.elements, batch.types, batch.roots, batch.diagnostics, batch::close);
    }

    boolean hasWarmContext() {
        return compiler.isWarm();
    }

    @Override
    public ParseTask parse(Path file) {
        var parser = Parser.parseJavaFileObject(new SourceFileObject(file));
        return new ParseTask(parser.task, parser.root, parser.hasSyntaxErrors);
    }

    @Override
    public ParseTask parse(JavaFileObject file) {
        var parser = Parser.parseJavaFileObject(file);
        return new ParseTask(parser.task, parser.root, parser.hasSyntaxErrors);
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
        return cacheContainsWord.getOrLoad(
                file, word, () -> StringSearch.containsWord(file, word));
    }

    private static final Cache<Void, List<String>> cacheContainsType = new Cache<>("helper.contains_type");

    private boolean containsType(Path file, String className) {
        return declaredTypes(file).contains(className);
    }

    static List<String> declaredTypes(Path file) {
        return cacheContainsType.getOrLoad(file, null, () -> {
            var root = Parser.parseJavaFileObject(new SourceFileObject(file)).root;
            var types = new ArrayList<String>();
            new FindTypeDeclarations().scan(root, types);
            return types;
        });
    }

    private boolean ownsSourceType(String name) {
        return findTypeDeclaration(name.replace('$', '.')) != NOT_FOUND;
    }

    private final Cache<Void, List<String>> cacheFileImports = new Cache<>("helper.file_imports");

    private List<String> readImports(Path file) {
        return cacheFileImports.getOrLoad(file, null, () -> loadImports(file));
    }

    private static final Pattern CLASS_DECLARATION_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\b");

    private List<String> loadImports(Path file) {
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
        return list;
    }

    private boolean containsImport(Path file, String className) {
        var pkg = packageName(className);
        if (pkg.equals(FileStore.packageName(file))) return true;
        if (pkg.equals("java.lang")) return true;
        var packageStar = pkg + ".*";
        var staticStar = className + ".*";
        var staticMemberPrefix = className + ".";
        for (var i : readImports(file)) {
            if (i.equals(className)
                    || className.startsWith(i + ".")
                    || i.equals(packageStar)
                    || i.equals(staticStar)
                    || i.startsWith(staticMemberPrefix))
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
            if (!isSourceVisible(f)) continue;
            all.addAll(readImports(f));
        }
        return all;
    }

    @Override
    public List<String> publicTopLevelTypes() {
        var all = new ArrayList<String>();
        for (var file : FileStore.all()) {
            if (!isSourceVisible(file)) continue;
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
    public Iterable<Path> search(String query) {
        Predicate<Path> test = f -> StringSearch.containsWordMatching(f, query);
        return () -> FileStore.all().stream().filter(this::isSourceVisible).filter(test).iterator();
    }

    @Override
    public Optional<JavaFileObject> findAnywhere(String className) {
        var fromSource = findTypeDeclaration(className);
        if (fromSource != NOT_FOUND) return Optional.of(new SourceFileObject(fromSource));
        var fromDocs = findPublicTypeDeclarationInDocPath(className);
        if (fromDocs.isPresent()) return fromDocs;
        var fromJdk = findPublicTypeDeclarationInJdk(className);
        if (fromJdk.isPresent()) return fromJdk;
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
            if (!isSourceVisible(f)) continue;
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
        if (!isSourceVisible(file)) return NOT_FOUND;
        if (!containsType(file, className)) return NOT_FOUND;
        return file;
    }

    @Override
    public Path[] findTypeReferences(String className) {
        var candidates = new ArrayList<Path>();
        for (var file : FileStore.all()) {
            if (referencesType(file, className)) candidates.add(file);
        }
        return candidates.toArray(Path[]::new);
    }

    @Override
    public Path[] findTypeReferences(Collection<String> classNames) {
        var names = classNames.stream().filter(name -> name != null && !name.isBlank()).distinct().toList();
        if (names.size() == 1) return findTypeReferences(names.getFirst());
        if (names.isEmpty()) return new Path[0];

        var simpleNames = names.stream()
                .map(this::simpleName)
                .filter(name -> !name.isBlank())
                .distinct()
                .map(StringSearch::new)
                .toList();
        var candidates = new ArrayList<Path>();
        for (var file : FileStore.all()) {
            if (!StringSearch.containsAnyWord(file, simpleNames)) continue;
            for (var className : names) {
                if (referencesType(file, className)) {
                    candidates.add(file);
                    break;
                }
            }
        }
        return candidates.toArray(Path[]::new);
    }

    private boolean referencesType(Path file, String className) {
        var pkg = packageName(className);
        return (pkg.isEmpty() || containsWord(file, pkg))
                && (containsImport(file, className) || containsWord(file, className))
                && containsWord(file, simpleName(className));
    }

    @Override
    public Path[] findMemberReferences(String className, String memberName) {
        var pkg = packageName(className);
        var candidates = new ArrayList<Path>();
        for (var f : FileStore.all()) {
            if (containsWord(f, memberName) && (pkg.equals(FileStore.packageName(f)) || containsImport(f, className))) {
                candidates.add(f);
            }
        }
        return candidates.toArray(Path[]::new);
    }

    private volatile ExternalBinaryDecompiler decompiler;

    @Override
    public Optional<Path> decompileClass(String qualifiedName) {
        if (ownsSourceType(qualifiedName)) return Optional.empty();
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

    @Override
    public Optional<byte[]> findClassFile(String qualifiedName) {
        if (ownsSourceType(qualifiedName)) return Optional.empty();
        var relative = qualifiedName.replace('.', '/') + ".class";
        for (var root : classPath) {
            if (Files.isDirectory(root)) {
                var classFile = root.resolve(relative);
                if (Files.exists(classFile)) {
                    try {
                        return Optional.of(Files.readAllBytes(classFile));
                    } catch (IOException e) {
                        LOG.fine("[classfile] failed to read " + classFile + ": " + e.getMessage());
                    }
                }
            } else if (root.toString().endsWith(".jar") && Files.exists(root)) {
                try (var jar = new JarFile(root.toFile())) {
                    var entry = jar.getEntry(relative);
                    if (entry != null) {
                        try (var in = jar.getInputStream(entry)) {
                            return Optional.of(in.readAllBytes());
                        }
                    }
                } catch (IOException e) {
                    LOG.fine("[classfile] failed to read " + relative + " from " + root.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return Optional.empty();
    }
    @Override
    public void close() {
        compiler.discard("compiler_closed");
        try {
            fileManager.close();
            docsFileManager.close();
        } catch (IOException e) {
            LOG.fine("Closing compiler file managers: " + e.getMessage());
        }
    }
}
