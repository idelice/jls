package org.javacs;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.*;

class JavaCompilerService implements CompilerProvider {
    private static final Logger LOG = Logger.getLogger("main");
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

    private static final int MAX_CACHE_SIZE = 1000;

    private CompileBatch cachedCompile;
    private final Map<JavaFileObject, SourceFingerprint> cachedModified =
        new LinkedHashMap<JavaFileObject, SourceFingerprint>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<JavaFileObject, SourceFingerprint> eldest) {
            boolean remove = size() > MAX_CACHE_SIZE;
            if (remove) {
                LOG.fine("Cache eviction: removing oldest entry");
            }
            return remove;
        }
    };
    private long cachedCompileContentRevision = -1;
    private CompileBatch cachedFastCompile;
    private final Map<JavaFileObject, SourceFingerprint> cachedFastModified =
        new LinkedHashMap<JavaFileObject, SourceFingerprint>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<JavaFileObject, SourceFingerprint> eldest) {
            boolean remove = size() > MAX_CACHE_SIZE;
            if (remove) {
                LOG.fine("Cache eviction: removing oldest entry");
            }
            return remove;
        }
    };
    private long cachedFastCompileContentRevision = -1;
    private CompileBatch cachedFastCompileNoAp;
    private final Map<JavaFileObject, SourceFingerprint> cachedFastNoApModified =
        new LinkedHashMap<JavaFileObject, SourceFingerprint>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<JavaFileObject, SourceFingerprint> eldest) {
            boolean remove = size() > MAX_CACHE_SIZE;
            if (remove) {
                LOG.fine("Cache eviction: removing oldest entry");
            }
            return remove;
        }
    };
    private long cachedFastCompileNoApContentRevision = -1;
    private final Map<String, Optional<JavaFileObject>> jdkSourceCache = new ConcurrentHashMap<>();
    private static final Map<Path, ParsedUnit> SHARED_PARSED_UNITS = new ConcurrentHashMap<>();
    final Map<Path, ParsedUnit> parsedUnits = SHARED_PARSED_UNITS;
    private volatile long lombokTypeIndexContentRevision = -1;
    private volatile LombokTypeIndex lombokTypeIndex = LombokTypeIndex.empty();

    static class ParsedUnit {
        final ParseTask task;
        final SourceFingerprint fingerprint;

        ParsedUnit(ParseTask task, SourceFingerprint fingerprint) {
            this.task = task;
            this.fingerprint = fingerprint;
        }
    }

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

    private static class LombokTypeIndex {
        final Set<String> lombokTypes;
        final Map<String, Path> byQualifiedName;
        final Map<String, Set<String>> bySimpleName;

        LombokTypeIndex(
                Set<String> lombokTypes,
                Map<String, Path> byQualifiedName,
                Map<String, Set<String>> bySimpleName) {
            this.lombokTypes = lombokTypes;
            this.byQualifiedName = byQualifiedName;
            this.bySimpleName = bySimpleName;
        }

        static LombokTypeIndex empty() {
            return new LombokTypeIndex(Set.of(), Map.of(), Map.of());
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

        if (mode == CompileBatch.AnalysisMode.ATTR) {
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
        var attrWithoutAp = mode == CompileBatch.AnalysisMode.ATTR && !allowAP;
        var compileCache =
                mode == CompileBatch.AnalysisMode.FULL
                        ? cachedCompile
                        : attrWithoutAp ? cachedFastCompileNoAp : cachedFastCompile;
        var modifiedCache =
                mode == CompileBatch.AnalysisMode.FULL
                        ? cachedModified
                        : attrWithoutAp ? cachedFastNoApModified : cachedFastModified;
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
        } else if (attrWithoutAp) {
            cachedFastCompileNoAp = loaded;
            cachedFastCompileNoApContentRevision = contentRevision;
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
        LOG.info(
                String.format(
                        "[perf] compile_trigger entry=compileBatch request=%s mode=%s allow_ap=%s sources=%d stack=%s",
                        requestType(),
                        mode.name().toLowerCase(),
                        allowAP,
                        sources.size(),
                        requestStack()));
        boolean useAP = allowAP && lombokApEnabled;
        var effectiveSources = expandSourcesForLombokAP(sources, useAP);
        var attrWithoutAp = mode == CompileBatch.AnalysisMode.ATTR && !useAP;
        var modifiedCache =
                mode == CompileBatch.AnalysisMode.FULL
                        ? cachedModified
                        : attrWithoutAp ? cachedFastNoApModified : cachedFastModified;
        var currentContentRevision = FileStore.contentRevision();
        var cachedContentRevision =
                mode == CompileBatch.AnalysisMode.FULL
                        ? cachedCompileContentRevision
                        : attrWithoutAp ? cachedFastCompileNoApContentRevision : cachedFastCompileContentRevision;
        if (cachedContentRevision != currentContentRevision || needsCompile(effectiveSources, modifiedCache)) {
            if (cachedContentRevision != currentContentRevision) {
                LOG.fine(
                        String.format(
                                "[perf] javac_cache_refresh mode=%s reason=content_revision_change cached=%d current=%d",
                                mode.name().toLowerCase(), cachedContentRevision, currentContentRevision));
            }
            loadCompile(effectiveSources, mode, useAP, currentContentRevision);
        } else {
            LOG.fine(
                    String.format(
                            "[perf] javac_cache_hit mode=%s content_revision=%d",
                            mode.name().toLowerCase(), currentContentRevision));
        }
        return mode == CompileBatch.AnalysisMode.FULL
                ? cachedCompile
                : attrWithoutAp ? cachedFastCompileNoAp : cachedFastCompile;
    }

    private Collection<? extends JavaFileObject> expandSourcesForLombokAP(
            Collection<? extends JavaFileObject> sources, boolean allowAP) {
        LOG.fine(
                String.format(
                        "[perf] compile_trigger entry=expandSourcesForLombokAP request=%s allow_ap=%s sources=%d stack=%s",
                        requestType(), allowAP, sources.size(), requestStack()));
        if (!allowAP || !lombokPresentOnClasspath) {
            return sources;
        }

        var requestedHasLombokAnnotations = requestedSourcesUseLombokAnnotations(sources);
        var referencedLombokSources = referencedLombokSources(sources);
        if (!requestedHasLombokAnnotations && referencedLombokSources.isEmpty()) {
            LOG.fine(
                    String.format(
                            "[perf] lombok_ap_sources requested=%d expanded=%d reason=no_lombok_annotations_or_references",
                            sources.size(), sources.size()));
            return sources;
        }

        var expanded = new LinkedHashMap<Path, JavaFileObject>();
        var nonFileSources = new ArrayList<JavaFileObject>();
        for (var source : sources) {
            var path = sourcePath(source);
            if (path == null) {
                nonFileSources.add(source);
                continue;
            }
            expanded.put(path, source);
        }

        for (var lombokSource : referencedLombokSources) {
            expanded.putIfAbsent(lombokSource, new SourceFileObject(lombokSource));
        }

        var reason =
                requestedHasLombokAnnotations
                        ? (referencedLombokSources.isEmpty()
                                ? "requested_lombok_annotations"
                                : "annotations_and_references")
                        : "referenced_lombok_types";

        if (expanded.size() + nonFileSources.size() == sources.size()) {
            LOG.fine(
                    String.format(
                            "[perf] lombok_ap_sources requested=%d expanded=%d reason=%s",
                            sources.size(), sources.size(), reason));
            return sources;
        }

        LOG.fine(
                String.format(
                        "[perf] lombok_ap_references requested=%d referenced=%d",
                        sources.size(), referencedLombokSources.size()));

        var result = new ArrayList<JavaFileObject>(expanded.values());
        result.addAll(nonFileSources);
        LOG.fine(
                String.format(
                        "[perf] lombok_ap_sources requested=%d expanded=%d reason=%s",
                        sources.size(), result.size(), reason));
        return result;
    }

    private Set<Path> referencedLombokSources(Collection<? extends JavaFileObject> sources) {
        var requestedPaths = new ArrayList<Path>();
        for (var source : sources) {
            var path = sourcePath(source);
            if (path != null) {
                requestedPaths.add(path);
            }
        }
        if (requestedPaths.isEmpty()) {
            return Set.of();
        }

        var index = currentLombokTypeIndex();
        if (index.lombokTypes.isEmpty()) {
            return Set.of();
        }

        var requestedSet = new LinkedHashSet<>(requestedPaths);
        var visited = new LinkedHashSet<>(requestedPaths);
        var pending = new ArrayDeque<>(requestedPaths);
        var referenced = new LinkedHashSet<Path>();
        while (!pending.isEmpty()) {
            var requested = pending.removeFirst();
            for (var candidate : referencedLombokSourcesIn(requested, index)) {
                if (!visited.add(candidate)) {
                    continue;
                }
                referenced.add(candidate);
                pending.addLast(candidate);
            }
        }
        referenced.removeAll(requestedSet);
        return referenced;
    }

    private Set<Path> referencedLombokSourcesIn(Path source, LombokTypeIndex index) {
        var referenced = new LinkedHashSet<Path>();
        var explicitImports = new HashMap<String, String>();
        var importedPackages = new LinkedHashSet<String>();

        var packageName = FileStore.packageName(source);
        if (packageName != null && !packageName.isBlank()) {
            importedPackages.add(packageName);
        }
        for (var imported : readImports(source)) {
            if (imported.endsWith(".*")) {
                importedPackages.add(imported.substring(0, imported.length() - 2));
            } else {
                explicitImports.put(simpleName(imported), imported);
            }
        }

        for (var typeReference : collectReferencedTypeNames(source)) {
            addResolvedLombokSource(typeReference, explicitImports, importedPackages, index, referenced);
        }
        return referenced;
    }

    private Set<String> collectReferencedTypeNames(Path source) {
        var referenced = new LinkedHashSet<String>();
        ParseTask parsed;
        try {
            parsed = parse(source);
        } catch (RuntimeException e) {
            LOG.fine(
                    String.format(
                            "[perf] lombok_reference_scan file=%s parsed=false reason=%s",
                            source.getFileName(), e.getMessage()));
            return referenced;
        }

        new TreeScanner<Void, Set<String>>() {
            @Override
            public Void visitClass(ClassTree node, Set<String> refs) {
                addTypeTokens(node.getExtendsClause(), refs);
                for (var iface : node.getImplementsClause()) {
                    addTypeTokens(iface, refs);
                }
                return super.visitClass(node, refs);
            }

            @Override
            public Void visitMethod(MethodTree node, Set<String> refs) {
                addTypeTokens(node.getReturnType(), refs);
                for (var parameter : node.getParameters()) {
                    addTypeTokens(parameter.getType(), refs);
                }
                return super.visitMethod(node, refs);
            }

            @Override
            public Void visitVariable(VariableTree node, Set<String> refs) {
                addTypeTokens(node.getType(), refs);
                return super.visitVariable(node, refs);
            }

            @Override
            public Void visitNewClass(NewClassTree node, Set<String> refs) {
                addTypeTokens(node.getIdentifier(), refs);
                return super.visitNewClass(node, refs);
            }
        }.scan(parsed.root, referenced);
        return referenced;
    }

    private void addTypeTokens(Tree tree, Set<String> referenced) {
        if (tree == null) {
            return;
        }
        var text = tree.toString();
        if (text == null || text.isBlank()) {
            return;
        }

        var qualified = QUALIFIED_TYPE_PATTERN.matcher(text);
        while (qualified.find()) {
            referenced.add(qualified.group());
        }
        var simple = SIMPLE_TYPE_PATTERN.matcher(text);
        while (simple.find()) {
            referenced.add(simple.group());
        }
    }

    private void addResolvedLombokSource(
            String typeReference,
            Map<String, String> explicitImports,
            Set<String> importedPackages,
            LombokTypeIndex index,
            Set<Path> referenced) {
        if (typeReference == null || typeReference.isBlank()) {
            return;
        }

        var direct = index.byQualifiedName.get(typeReference);
        if (direct != null) {
            referenced.add(direct);
            return;
        }

        var simple = typeReference;
        var lastDot = simple.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < simple.length()) {
            simple = simple.substring(lastDot + 1);
        }

        var explicit = explicitImports.get(simple);
        if (explicit != null) {
            var imported = index.byQualifiedName.get(explicit);
            if (imported != null) {
                referenced.add(imported);
                return;
            }
        }

        for (var importedPackage : importedPackages) {
            var candidate = importedPackage + "." + simple;
            var resolved = index.byQualifiedName.get(candidate);
            if (resolved != null) {
                referenced.add(resolved);
            }
        }

        var bySimple = index.bySimpleName.get(simple);
        if (bySimple == null || bySimple.size() != 1) {
            return;
        }
        var qualified = bySimple.iterator().next();
        var resolved = index.byQualifiedName.get(qualified);
        if (resolved != null) {
            referenced.add(resolved);
        }
    }

    private LombokTypeIndex currentLombokTypeIndex() {
        var revision = FileStore.contentRevision();
        if (lombokTypeIndexContentRevision == revision) {
            return lombokTypeIndex;
        }
        synchronized (this) {
            if (lombokTypeIndexContentRevision == revision) {
                return lombokTypeIndex;
            }
            var byQualifiedName = new Object2ObjectLinkedOpenHashMap<String, Path>();
            var bySimpleName = new Object2ObjectOpenHashMap<String, Set<String>>();
            for (var source : FileStore.all()) {
                if (!hasLombokAnnotation(source)) continue;
                var simple = simpleTypeName(source);
                var pkg = FileStore.packageName(source);
                var qualified = pkg == null || pkg.isBlank() ? simple : pkg + "." + simple;
                byQualifiedName.putIfAbsent(qualified, source);
                bySimpleName.computeIfAbsent(simple, __ -> new ObjectLinkedOpenHashSet<>()).add(qualified);
            }

            var copiedSimple = new Object2ObjectOpenHashMap<String, Set<String>>();
            for (var entry : bySimpleName.entrySet()) {
                copiedSimple.put(
                        entry.getKey(),
                        Collections.unmodifiableSet(new ObjectLinkedOpenHashSet<>(entry.getValue())));
            }

            lombokTypeIndex =
                    new LombokTypeIndex(
                            Collections.unmodifiableSet(new ObjectLinkedOpenHashSet<>(byQualifiedName.keySet())),
                            Collections.unmodifiableMap(byQualifiedName),
                            Collections.unmodifiableMap(copiedSimple));
            lombokTypeIndexContentRevision = revision;
            LOG.fine(
                    String.format(
                            "[perf] lombok_type_index revision=%d types=%d",
                            revision, lombokTypeIndex.lombokTypes.size()));
            return lombokTypeIndex;
        }
    }

    private boolean requestedSourcesUseLombokAnnotations(Collection<? extends JavaFileObject> sources) {
        for (var source : sources) {
            var path = sourcePath(source);
            if (path == null) {
                continue;
            }
            if (quickMaybeUsesLombok(path)) {
                LOG.fine("[perf] lombok_ap_gate enabled=true file=" + path.getFileName());
                return true;
            }
        }
        return false;
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
        dropCachedCompilation(cachedFastCompileNoAp, cachedFastNoApModified);
        cachedFastCompileNoAp = null;
        cachedFastCompileNoApContentRevision = -1;
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
    private static final Pattern SIMPLE_EXTRACTOR = Pattern.compile("[A-Z][_a-zA-Z0-9]*$");
    private static final Pattern IMPORT_CLASS = Pattern.compile("^import +(static +)?([\\w\\.]+\\.\\w+);");
    private static final Pattern IMPORT_STAR = Pattern.compile("^import +(static +)?([\\w\\.]+\\.\\*);");
    private static final int LOMBOK_SCAN_LINE_LIMIT = 200;
    private static final Pattern QUALIFIED_TYPE_PATTERN =
            Pattern.compile("\\b(?:[a-z][_a-zA-Z0-9]*\\.)+[A-Z][_a-zA-Z0-9]*(?:\\.[A-Z][_a-zA-Z0-9]*)*\\b");
    private static final Pattern SIMPLE_TYPE_PATTERN = Pattern.compile("\\b[A-Z][_a-zA-Z0-9]*\\b");

    private String packageName(String className) {
        var m = PACKAGE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

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

    private final Cache<Void, Boolean> cacheHasLombokAnnotation = new Cache<>();

    private boolean hasLombokAnnotation(Path file) {
        if (cacheHasLombokAnnotation.needs(file, null)) {
            var hasLombok = false;
            try (var lines = FileStore.lines(file)) {
                hasLombok = LombokAnnotations.sourceMayRequireLombokExpansion(lines, LOMBOK_SCAN_LINE_LIMIT);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            LOG.fine(
                    String.format(
                            "[perf] lombok_scan file=%s lines=%d detected=%s",
                            file.getFileName(), LOMBOK_SCAN_LINE_LIMIT, hasLombok));
            cacheHasLombokAnnotation.load(file, null, hasLombok);
        }
        return cacheHasLombokAnnotation.get(file, null);
    }

    private boolean sourceUsesLombok(Path file) {
        return hasLombokAnnotation(file);
    }

    private boolean quickMaybeUsesLombok(Path file) {
        return hasLombokAnnotation(file);
    }

    private final Cache<Void, List<String>> cacheFileImports = new Cache<>();

    private List<String> readImports(Path file) {
        if (cacheFileImports.needs(file, null)) {
            loadImports(file);
        }
        return cacheFileImports.get(file, null);
    }

    private void loadImports(Path file) {
        var list = new ArrayList<String>();
        try (var lines = FileStore.lines(file)) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                // If we reach a class declaration, stop looking for imports
                if (line.contains("class") || line.contains("interface") || line.contains("enum")) break;
                // import foo.bar.Doh;
                var matchesClass = IMPORT_CLASS.matcher(line);
                if (matchesClass.matches()) {
                    list.add(matchesClass.group(2));
                }
                // import foo.bar.*
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
        var packageStar = packageName + ".*";
        var staticStar = className + ".*";
        var staticMemberPrefix = className + ".";
        for (var i : readImports(file)) {
            if (i.equals(className)
                    || i.equals(packageStar)
                    || i.equals(staticStar)
                    || i.startsWith(staticMemberPrefix)) {
                return true;
            }
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
            LOG.fine(String.format("[perf] jdk_lookup class=%s took=0ms cache=hit found=%s", className, cached.isPresent()));
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
                    LOG.fine(String.format("...found %s in module %s of jdk", fromModuleSourcePath.toUri(), module));
                    var found = Optional.of(fromModuleSourcePath);
                    jdkSourceCache.put(className, found);
                    LOG.fine(
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
        LOG.fine(
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
        var source = new SourceFileObject(file);
        return parseCached(source, file);
    }

    @Override
    public ParseTask parse(JavaFileObject file) {
        var filePath = sourcePath(file);
        if (filePath != null && FileStore.isJavaFile(filePath)) {
            return parseCached(file, filePath);
        }
        var parser = Parser.parseJavaFileObject(file);
        return new ParseTask(parser.task, parser.root);
    }

    private ParseTask parseCached(JavaFileObject file, Path filePath) {
        var currentFingerprint = fingerprint(file);
        var cached = parsedUnits.get(filePath);
        if (cached != null && currentFingerprint.equals(cached.fingerprint)) {
            LOG.fine(
                    String.format(
                            "[perf] parse_cache_hit file=%s version=%d modified=%d",
                            filePath.getFileName(),
                            currentFingerprint.version,
                            currentFingerprint.modifiedMillis));
            return cached.task;
        }
        var parser = Parser.parseJavaFileObject(file);
        var task = new ParseTask(parser.task, parser.root);
        parsedUnits.put(filePath, new ParsedUnit(task, currentFingerprint));
        LOG.fine(
                String.format(
                        "[perf] parse_cache_store file=%s version=%d modified=%d",
                        filePath.getFileName(),
                        currentFingerprint.version,
                        currentFingerprint.modifiedMillis));
        return task;
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
        LOG.info(
                String.format(
                        "[perf] compile_trigger entry=compile request=%s mode=full sources=%d stack=%s",
                        requestType(), sources.size(), requestStack()));
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
    public CompileTask compileFastWithProcessors(Collection<? extends JavaFileObject> sources) {
        LOG.info(
                String.format(
                        "[perf] compile_trigger entry=compileFastWithProcessors request=%s mode=attr sources=%d stack=%s",
                        requestType(), sources.size(), requestStack()));
        var compile = compileBatch(sources, CompileBatch.AnalysisMode.ATTR, true);
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps, compile::close);
    }

    @Override
    public CompileTask compileFast(Collection<? extends JavaFileObject> sources) {
        LOG.info(
                String.format(
                        "[perf] compile_trigger entry=compileFast request=%s mode=attr sources=%d stack=%s",
                        requestType(), sources.size(), requestStack()));
        var compile = compileBatch(sources, CompileBatch.AnalysisMode.ATTR, false);
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps, compile::close);
    }

    private String requestType() {
        for (var frame : Thread.currentThread().getStackTrace()) {
            if (!frame.getClassName().startsWith("org.javacs.")) continue;
            var method = frame.getMethodName();
            switch (method) {
                case "completion":
                case "hover":
                case "gotoDefinition":
                case "findReferences":
                case "signatureHelp":
                case "inlayHint":
                case "didOpenTextDocument":
                case "didChangeTextDocument":
                case "didSaveTextDocument":
                case "lint":
                    return method;
                default:
            }
        }
        return "unknown";
    }

    private String requestStack() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .filter(f -> f.getClassName().startsWith("org.javacs."))
                .filter(f -> !f.getClassName().equals(JavaCompilerService.class.getName()))
                .limit(6)
                .map(f -> f.getClassName() + "#" + f.getMethodName() + ":" + f.getLineNumber())
                .collect(Collectors.joining(" > "));
    }

    private static boolean hasLombokJar(Set<Path> classPath) {
        return classPath.stream()
                .anyMatch(
                        p -> {
                            var name = p.getFileName().toString().toLowerCase();
                            return name.startsWith("lombok") && (name.endsWith(".jar") || name.endsWith("-all.jar"));
                        });
    }

}
