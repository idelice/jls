package org.javacs;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.tools.*;

class JavaCompilerService implements CompilerProvider {
    private static final Logger LOG = Logger.getLogger("main");
    private static final int MAX_PARSE_CACHE_SIZE = 200;

    private enum CacheSlot {
        NONE,
        FULL,
        ATTR,
        ATTR_NO_AP
    }

    private record CompileProfile(
            CompileBatch.AnalysisMode analysisMode,
            boolean allowAnnotationProcessing,
            boolean expandAdditionalSources,
            CacheSlot cacheSlot,
            boolean widenToWorkspace) {}

    private static final CompileProfile FULL_PROFILE =
            new CompileProfile(CompileBatch.AnalysisMode.FULL, true, true, CacheSlot.FULL, true);
    private static final CompileProfile FAST_AP_PROFILE =
            new CompileProfile(CompileBatch.AnalysisMode.ATTR, true, true, CacheSlot.ATTR, false);
    private static final CompileProfile FAST_NO_AP_PROFILE =
            new CompileProfile(CompileBatch.AnalysisMode.ATTR, false, true, CacheSlot.ATTR_NO_AP, false);
    // Diagnostics: full analysis on the requested file(s) only.
    // Does NOT widen to FileStore.all() — pull diagnostics are strictly per-file.
    // expandAdditionalSources=true enables targeted Lombok source expansion: only Lombok-annotated
    // files directly referenced by the requested file are added to the compilation unit, so AP
    // generates their members and Lombok method calls resolve correctly. ErrorProvider still filters
    // emitted diagnostics to the requested URIs, so non-requested expanded roots are silent.
    private static final CompileProfile DIAGNOSTICS_PROFILE =
            new CompileProfile(CompileBatch.AnalysisMode.FULL, true, true, CacheSlot.NONE, false);

    // Not modifiable! If you want to edit these, you need to create a new instance
    final Set<Path> classPath, docPath;
    final Set<String> addExports;
    final List<String> extraArgs;
    final ReusableCompiler compiler = new ReusableCompiler();
    private final ReusableCompiler.SlotContext fullSlot = new ReusableCompiler.SlotContext();
    private final ReusableCompiler.SlotContext attrSlot = new ReusableCompiler.SlotContext();
    private final ReusableCompiler.SlotContext attrNoApSlot = new ReusableCompiler.SlotContext();
    final Set<String> jdkClasses, classPathClasses;
    final boolean lombokPresentOnClasspath;
    final boolean apEnabled;
    final String compilerRole;
    final boolean retainCachedCompileBatches;
    // Diagnostics from the last compilation task
    final List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
    // Use the same file manager for multiple tasks, so we don't repeatedly re-compile the same files
    // TODO intercept files that aren't in the batch and erase method bodies so compilation is faster
    final SourceFileManager fileManager;
    // Lane-local file manager for doc-path and JDK source lookups, configured from shared Docs metadata
    final SourceFileManager docsFileManager;

    /**
     * Primary constructor: all three compiler lanes are built from one shared resources object.
     * Mutable lane state (compilers, caches, file managers) is still independent per lane.
     */
    JavaCompilerService(CompilerSharedResources shared, String compilerRole) {
        var constructorStarted = Instant.now();

        // classPath can't actually be modified, because JavaCompiler remembers it from task to task
        this.classPath = shared.classPath();
        this.docPath = shared.docPath();
        this.addExports = shared.addExports();
        this.extraArgs = shared.extraArgs();
        this.jdkClasses = shared.jdkClasses();
        this.classPathClasses = shared.classPathClasses();
        this.lombokPresentOnClasspath = shared.lombokPresentOnClasspath();
        this.apEnabled = shared.apEnabled();
        this.compilerRole = compilerRole;
        this.retainCachedCompileBatches =
                !"background".equals(this.compilerRole)
                        && !"index".equals(this.compilerRole)
                        && !"diagnostics".equals(this.compilerRole);
        this.fileManager = new SourceFileManager();
        this.docsFileManager = shared.docs().createFileManager();
        LOG.info(String.format(
                "[perf] compiler_lane_init role=%s ap_enabled=%s retain_compile_batches=%s took=%dms",
                this.compilerRole,
                this.apEnabled,
                this.retainCachedCompileBatches,
                Duration.between(constructorStarted, Instant.now()).toMillis()));
    }

    /** Convenience constructor for tests and isolated callers that don't share resources across lanes. */
    JavaCompilerService(
            Set<Path> classPath, Set<Path> docPath, Set<String> addExports, Collection<String> extraArgs) {
        this(CompilerSharedResources.from(classPath, docPath, addExports, extraArgs), "standalone");
    }

    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports, Set<String> extraArgs) {
        this(classPath, docPath, addExports, (Collection<String>) extraArgs);
    }

    private CompileBatch cachedCompile;
    private long cachedCompileContentRevision = -1;
    private CompileBatch cachedFastCompile;
    private long cachedFastCompileContentRevision = -1;
    // URI of the single file compiled into this slot (completion always compiles one file).
    private URI cachedFastCompileUri = null;
    private CompileBatch cachedFastCompileNoAp;
    private long cachedFastCompileNoApContentRevision = -1;
    private URI cachedFastNoApCompileUri = null;
    private final Map<String, Optional<JavaFileObject>> jdkSourceCache = new ConcurrentHashMap<>();
    final Map<Path, ParsedUnit> parsedUnits =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<Path, ParsedUnit> eldest) {
                            return size() > MAX_PARSE_CACHE_SIZE;
                        }
                    });
    private volatile long lombokTypeIndexContentRevision = -1;
    private volatile LombokTypeIndex lombokTypeIndex = LombokTypeIndex.empty();
    private volatile CompileTelemetry lastCompileTelemetry = CompileTelemetry.empty();

    record ParsedUnit(ParseTask task, SourceFingerprint fingerprint) {}

    private record SourceFingerprint(long modifiedMillis, int version) {}

    private record LombokTypeIndex(
            Set<String> lombokTypes,
            Map<String, Path> byQualifiedName,
            Map<String, Set<String>> bySimpleName) {
        static LombokTypeIndex empty() {
            return new LombokTypeIndex(Set.of(), Map.of(), Map.of());
        }
    }

    private record ExpandedSources(
            Collection<? extends JavaFileObject> sources,
            boolean lombokExpansionUsed,
            int requestedCount,
            int expandedCount) {}

    record CompileTelemetry(
            String cacheName,
            String path,
            boolean annotationProcessingEnabled,
            boolean lombokExpansionUsed,
            int requestedSources,
            int expandedSources,
            long parseMs,
            long enterMs,
            long analyzeMs) {
        static CompileTelemetry empty() {
            return new CompileTelemetry("unknown", "unknown", false, false, 0, 0, -1, -1, -1);
        }
    }

    record MemorySnapshot(
            String label,
            long heapUsedMb,
            long heapCommittedMb,
            long nonHeapUsedMb,
            int fullRoots,
            int fullSourceStamps,
            boolean fullClosed,
            int attrRoots,
            int attrNoApRoots) {}

    MemorySnapshot memorySnapshot(String label) {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        return new MemorySnapshot(
                label,
                heap.getUsed() / (1024 * 1024),
                heap.getCommitted() / (1024 * 1024),
                nonHeap.getUsed() / (1024 * 1024),
                cachedCompile == null ? 0 : cachedCompile.roots.size(),
                cachedCompile == null ? 0 : cachedCompile.sourceStamps.size(),
                cachedCompile == null || cachedCompile.closed,
                cachedFastCompile == null ? 0 : cachedFastCompile.roots.size(),
                cachedFastCompileNoAp == null ? 0 : cachedFastCompileNoAp.roots.size());
    }

    void logMemorySnapshot(String label) {
        var snapshot = memorySnapshot(label);
        LOG.warning(
                String.format(
                        "[memory] label=%s role=%s heap_used=%dmb heap_committed=%dmb non_heap=%dmb full_roots=%d full_stamps=%d full_closed=%s attr_roots=%d attr_no_ap_roots=%d file_store_all=%d",
                        snapshot.label(),
                        compilerRole,
                        snapshot.heapUsedMb(),
                        snapshot.heapCommittedMb(),
                        snapshot.nonHeapUsedMb(),
                        snapshot.fullRoots(),
                        snapshot.fullSourceStamps(),
                        snapshot.fullClosed(),
                        snapshot.attrRoots(),
                        snapshot.attrNoApRoots(),
                        FileStore.all().size()));
    }

    private SourceFingerprint fingerprint(JavaFileObject file) {
        var version = -1;
        if (file instanceof SourceFileObject sourceFileObject) {
            version = sourceFileObject.contentVersion();
        }
        return new SourceFingerprint(file.getLastModified(), version);
    }

    private boolean fileMatchesAttrCache(CacheSlot slot, Collection<? extends JavaFileObject> sources) {
        var uri = sources.isEmpty() ? null : sources.iterator().next().toUri();
        var cached = slot == CacheSlot.ATTR ? cachedFastCompileUri : cachedFastNoApCompileUri;
        return Objects.equals(uri, cached);
    }

    private CompileBatch compileWithExpansionIfNeeded(
            Collection<? extends JavaFileObject> sources,
            CompileProfile profile,
            boolean useAnnotationProcessing,
            ReusableCompiler.SlotContext slot) {
        if (sources.isEmpty()) throw new RuntimeException("empty sources");

        var firstAttempt =
                createCompileBatch(sources, profile, useAnnotationProcessing, slot, "first-attempt");

        if (profile.analysisMode() == CompileBatch.AnalysisMode.ATTR
                || !profile.expandAdditionalSources()) {
            return firstAttempt;
        }

        Set<Path> addFiles;
        try {
            addFiles = firstAttempt.needsAdditionalSources();
        } catch (RuntimeException e) {
            firstAttempt.close();
            throw e;
        }

        if (addFiles.isEmpty()) {
            return firstAttempt;
        }
        LOG.info("...need to recompile with " + addFiles);
        firstAttempt.close();

        var moreSources = new ArrayList<JavaFileObject>(sources);
        for (var add : addFiles) {
            moreSources.add(new SourceFileObject(add));
        }

        return createCompileBatch(moreSources, profile, useAnnotationProcessing, slot, "second-attempt");
    }

    private CompileBatch createCompileBatch(
            Collection<? extends JavaFileObject> sources,
            CompileProfile profile,
            boolean useAnnotationProcessing,
            ReusableCompiler.SlotContext slot,
            String phase) {
        diags.clear();
        var options = buildOptions(profile.analysisMode(), useAnnotationProcessing);
        logMemorySnapshot(
                String.format(
                        "before_create_compile phase=%s mode=%s sources=%d ap=%s",
                        phase, profile.analysisMode(), sources.size(), useAnnotationProcessing));
        var batch = new CompileBatch(
                this, sources, useAnnotationProcessing, profile.analysisMode(), slot, options);
        logMemorySnapshot(
                String.format(
                        "after_create_compile phase=%s mode=%s sources=%d roots=%d ap=%s",
                        phase,
                        profile.analysisMode(),
                        sources.size(),
                        batch.roots.size(),
                        batch.annotationProcessingEnabled));
        return batch;
    }

    private List<String> buildOptions(CompileBatch.AnalysisMode mode, boolean allowAP) {
        if (!allowAP) return CompileBatch.optionsWithoutAP(classPath, addExports, extraArgs);
        if (mode == CompileBatch.AnalysisMode.ATTR)
            return CompileBatch.optionsForFastAp(classPath, addExports, extraArgs, lombokPresentOnClasspath);
        return CompileBatch.options(classPath, addExports, extraArgs, lombokPresentOnClasspath);
    }

    private ReusableCompiler.SlotContext slotFor(CacheSlot cacheSlot) {
        return switch (cacheSlot) {
            case NONE -> throw new IllegalArgumentException("CacheSlot.NONE has no reusable compiler slot");
            case FULL -> fullSlot;
            case ATTR -> attrSlot;
            case ATTR_NO_AP -> attrNoApSlot;
        };
    }

    private CompileBatch refreshCachedCompile(
            Collection<? extends JavaFileObject> sources,
            CompileProfile profile,
            boolean useAnnotationProcessing,
            long contentRevision) {
        var cacheSlot = cacheSlot(profile, useAnnotationProcessing);
        var compileCache = cachedCompileFor(cacheSlot);
        if (compileCache != null && !compileCache.closed) {
            LOG.warning(String.format(
                    "[compiler] stale cache slot detected role=%s mode=%s; closing and recovering",
                    compilerRole, profile.analysisMode()));
            compileCache.close();
        }

        var slot = slotFor(cacheSlot);
        var loaded =
                compileWithExpansionIfNeeded(sources, profile, useAnnotationProcessing, slot);

        storeCachedCompile(cacheSlot, loaded, contentRevision);
        logMemorySnapshot(
                String.format(
                        "after_store_cache slot=%s sources=%d roots=%d revision=%d",
                        cacheSlot, sources.size(), loaded.roots.size(), contentRevision));
        // Track the compiled file URI for per-file hit validation on ATTR slots.
        if (cacheSlot != CacheSlot.FULL && !sources.isEmpty()) {
            var uri = sources.iterator().next().toUri();
            if (cacheSlot == CacheSlot.ATTR) cachedFastCompileUri = uri;
            else cachedFastNoApCompileUri = uri;
        }
        return loaded;
    }

    private CompileTelemetry compileTelemetry(
            String cacheName,
            String path,
            boolean useAP,
            ExpandedSources expandedSources,
            CompileBatch compile) {
        return new CompileTelemetry(
                cacheName,
                path,
                useAP,
                expandedSources.lombokExpansionUsed(),
                expandedSources.requestedCount(),
                expandedSources.expandedCount(),
                compile == null ? -1 : compile.parseMs,
                compile == null ? -1 : compile.enterMs,
                compile == null ? -1 : compile.analyzeMs);
    }

    private CompileBatch compileBatch(
            Collection<? extends JavaFileObject> sources, CompileProfile profile) {
        var useAnnotationProcessing = profile.allowAnnotationProcessing() && apEnabled;
        // FULL_PROFILE widens to FileStore.all() so definition/hover/reference requests warm a
        // workspace-scoped cache for all subsequent interactive callers at the same revision.
        // DIAGNOSTICS_PROFILE keeps the caller's source set to avoid retaining a full workspace
        // compile batch on every BufEnter pull-diagnostic request.
        var compileSources =
                profile.cacheSlot() == CacheSlot.FULL && profile.widenToWorkspace()
                        ? FileStore.all().stream().<JavaFileObject>map(SourceFileObject::new).toList()
                        : sources;
        var expandedSources =
                profile.expandAdditionalSources()
                        ? expandSourcesForLombokAPDetails(compileSources, useAnnotationProcessing)
                        : new ExpandedSources(
                                compileSources,
                                false,
                                compileSources.size(),
                                compileSources.size());
        var effectiveSources = expandedSources.sources();
        var cacheSlot = cacheSlot(profile, useAnnotationProcessing);
        var cacheName = cacheMetricNameFor(cacheSlot);
        if (!retainCachedCompileBatches) {
            CacheAudit.miss(cacheName);
            var loaded =
                    compileWithExpansionIfNeeded(
                            effectiveSources,
                            profile,
                            useAnnotationProcessing,
                            null);
            lastCompileTelemetry =
                    compileTelemetry(
                            cacheName,
                            "uncached_role",
                            loaded.annotationProcessingEnabled,
                            expandedSources,
                            loaded);
            return loaded;
        }
        var currentContentRevision = FileStore.contentRevision();
        var cachedContentRevision = cachedContentRevisionFor(cacheSlot);
        var cachedCompile = cachedCompileFor(cacheSlot);
        // FULL: revision match is sufficient — always workspace-scoped, no per-file tracking needed.
        // ATTR/ATTR_NO_AP: revision match + same file (completion is per-file).
        var cacheStale =
                cachedContentRevision != currentContentRevision
                        || (cacheSlot != CacheSlot.FULL
                                && !fileMatchesAttrCache(cacheSlot, effectiveSources));
        var cacheReusable = cachedCompile != null && !cachedCompile.closed;
        if (cacheStale || !cacheReusable) {
            if (cacheSlot == CacheSlot.FULL) {
                LOG.fine(
                        String.format(
                            "[cache] compile_batch decision=miss role=%s cache=%s cached_rev=%d current_rev=%d",
                                compilerRole,
                                cacheName,
                                cachedContentRevision,
                                currentContentRevision));
            }
            if (!cacheStale && cachedCompile != null && cachedCompile.closed) {
                LOG.fine(
                        String.format(
                                "[perf] compile_batch_refresh reason=closed_cache cache=%s revision=%d",
                                cacheName,
                                currentContentRevision));
            }
            CacheAudit.miss(cacheName);
            var loaded =
                    refreshCachedCompile(
                            effectiveSources, profile, useAnnotationProcessing, currentContentRevision);
            CacheAudit.load(cacheName);
            if (loaded.annotationProcessingEnabled == useAnnotationProcessing) {
                CacheAudit.store(cacheName);
            }
            var compilerPath =
                    useAnnotationProcessing && !loaded.annotationProcessingEnabled
                            ? "ap_fallback_no_cache"
                            : "cache_refresh";
            lastCompileTelemetry =
                    compileTelemetry(
                            cacheName,
                            compilerPath,
                            loaded.annotationProcessingEnabled,
                            expandedSources,
                            loaded);
            return loaded;
        } else {
            if (cacheSlot == CacheSlot.FULL) {
                LOG.fine(
                        String.format(
                                "[cache] compile_batch decision=hit role=%s cache=%s revision=%d",
                                compilerRole,
                                cacheName,
                                currentContentRevision));
            }
            CacheAudit.hit(cacheName);
            lastCompileTelemetry =
                    compileTelemetry(
                            cacheName, "cache_hit", useAnnotationProcessing, expandedSources, null);
        }
        return cachedCompileFor(cacheSlot);
    }

    private CacheSlot cacheSlot(CompileProfile profile, boolean useAnnotationProcessing) {
        if (profile.cacheSlot() == CacheSlot.ATTR && !useAnnotationProcessing) {
            return CacheSlot.ATTR_NO_AP;
        }
        return profile.cacheSlot();
    }

    private CompileBatch cachedCompileFor(CacheSlot cacheSlot) {
        return switch (cacheSlot) {
            case NONE -> null;
            case FULL -> cachedCompile;
            case ATTR -> cachedFastCompile;
            case ATTR_NO_AP -> cachedFastCompileNoAp;
        };
    }

    private long cachedContentRevisionFor(CacheSlot cacheSlot) {
        return switch (cacheSlot) {
            case NONE -> -1;
            case FULL -> cachedCompileContentRevision;
            case ATTR -> cachedFastCompileContentRevision;
            case ATTR_NO_AP -> cachedFastCompileNoApContentRevision;
        };
    }

    private void storeCachedCompile(CacheSlot cacheSlot, CompileBatch loaded, long contentRevision) {
        var old = cachedCompileFor(cacheSlot);
        if (old != null && !old.closed) {
            old.close();
        }
        switch (cacheSlot) {
            case NONE -> throw new IllegalArgumentException("CacheSlot.NONE cannot store compile batches");
            case FULL -> {
                cachedCompile = loaded;
                cachedCompileContentRevision = contentRevision;
            }
            case ATTR -> {
                cachedFastCompile = loaded;
                cachedFastCompileContentRevision = contentRevision;
            }
            case ATTR_NO_AP -> {
                cachedFastCompileNoAp = loaded;
                cachedFastCompileNoApContentRevision = contentRevision;
            }
        }
    }

    private String cacheMetricNameFor(CacheSlot cacheSlot) {
        return switch (cacheSlot) {
            case NONE -> "javac.none";
            case FULL -> "javac.full";
            case ATTR -> "javac.attr";
            case ATTR_NO_AP -> "javac.attr_no_ap";
        };
    }

    private ExpandedSources expandSourcesForLombokAPDetails(
            Collection<? extends JavaFileObject> sources, boolean allowAP) {
        if (!allowAP || !lombokPresentOnClasspath) {
            return new ExpandedSources(sources, false, sources.size(), sources.size());
        }

        var requestedHasLombokAnnotations = requestedSourcesUseLombokAnnotations(sources);
        var referencedLombokSources = referencedLombokSources(sources);
        if (!requestedHasLombokAnnotations && referencedLombokSources.isEmpty()) {
            return new ExpandedSources(sources, false, sources.size(), sources.size());
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

        if (expanded.size() + nonFileSources.size() == sources.size()) {
            return new ExpandedSources(sources, true, sources.size(), sources.size());
        }

        var result = new ArrayList<>(expanded.values());
        result.addAll(nonFileSources);
        return new ExpandedSources(result, true, sources.size(), result.size());
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

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Set<String> refs) {
                addTypeTokens(node.getExpression(), refs);
                return super.visitMemberSelect(node, refs);
            }
        }.scan(parsed.root(), referenced);
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
            return lombokTypeIndex;
        }
    }

    private boolean requestedSourcesUseLombokAnnotations(Collection<? extends JavaFileObject> sources) {
        for (var source : sources) {
            var path = sourcePath(source);
            if (path == null) {
                continue;
            }
            if (hasLombokAnnotation(path)) {
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

    private final Cache<Void, Boolean> cacheHasLombokAnnotation = new Cache<>("helper.has_lombok_annotation");

    private boolean hasLombokAnnotation(Path file) {
        if (cacheHasLombokAnnotation.needs(file, null)) {
            cacheHasLombokAnnotation.load(file, null,LombokAnnotations.sourceMayRequireLombokExpansion(file, LOMBOK_SCAN_LINE_LIMIT));
        }
        return cacheHasLombokAnnotation.get(file, null);
    }

    private final Cache<Void, List<String>> cacheFileImports = new Cache<>("helper.file_imports");

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

    private volatile org.javacs.completion.ExternalBinaryDecompiler decompiler;

    private org.javacs.completion.ExternalBinaryDecompiler decompiler() {
        if (decompiler == null) {
            synchronized (this) {
                if (decompiler == null) {
                    var fingerprint =
                            Integer.toHexString(
                                    classPath.stream()
                                            .map(p -> p.toAbsolutePath().normalize().toString())
                                            .sorted()
                                            .collect(java.util.stream.Collectors.joining("|"))
                                            .hashCode());
                    decompiler = new org.javacs.completion.ExternalBinaryDecompiler(
                            classPath, fingerprint, getClass().getClassLoader());
                }
            }
        }
        return decompiler;
    }

    @Override
    public Optional<Path> decompileClass(String qualifiedName) {
        return decompiler().decompileSourcePath(qualifiedName);
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
                    docsFileManager.getJavaFileForInput(
                            StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
            return Optional.ofNullable(found);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<JavaFileObject> findPublicTypeDeclarationInJdk(String className) {
        var cached = jdkSourceCache.get(className);
        if (cached != null) {
            return cached;
        }
        try {
            for (var module : ScanClassPath.JDK_MODULES) {
                var moduleLocation = docsFileManager.getLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module);
                if (moduleLocation == null) continue;
                var fromModuleSourcePath =
                        docsFileManager.getJavaFileForInput(moduleLocation, className, JavaFileObject.Kind.SOURCE);
                if (fromModuleSourcePath != null) {
                    LOG.fine(String.format("...found %s in module %s of jdk", fromModuleSourcePath.toUri(), module));
                    var found = Optional.of(fromModuleSourcePath);
                    jdkSourceCache.put(className, found);
                    return found;
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
            CacheAudit.hit("parse." + compilerRole);
            return cached.task;
        }
        CacheAudit.miss("parse." + compilerRole);
        var parser = Parser.parseJavaFileObject(file);
        var task = new ParseTask(parser.task, parser.root);
        parsedUnits.put(filePath, new ParsedUnit(task, currentFingerprint));
        CacheAudit.load("parse." + compilerRole);
        CacheAudit.store("parse." + compilerRole);
        return task;
    }

    private boolean isCachedBatch(CompileBatch batch) {
        return batch != null
                && (batch == cachedCompile
                        || batch == cachedFastCompile
                        || batch == cachedFastCompileNoAp);
    }

    @Override
    public CompileTask compile(Path... files) {
        return compile(toSourceFiles(files));
    }

    @Override
    public CompileTask compile(Collection<? extends JavaFileObject> sources) {
        var compile = compileBatch(sources, FULL_PROFILE);
        LOG.fine(String.format("[cache] compile role=%s cached=%s", compilerRole, isCachedBatch(compile)));
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps,
                isCachedBatch(compile) ? () -> {} : compile::close);
    }

    CompileTask compileDiagnostics(Collection<? extends JavaFileObject> sources) {
        // DIAGNOSTICS_PROFILE: full analysis on the requested file(s) only, no workspace widening.
        // The diagnostics lane never retains a CompileBatch so close() is always real.
        var compile = compileBatch(sources, DIAGNOSTICS_PROFILE);
        LOG.fine(String.format("[cache] compileDiagnostics role=%s cached=%s", compilerRole, isCachedBatch(compile)));
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps,
                isCachedBatch(compile) ? () -> {} : compile::close);
    }

    @Override
    public CompileTask compileFast(Path... files) {
        return compileFast(toSourceFiles(files));
    }

    @Override
    public CompileTask compileFastWithProcessors(Path... files) {
        return compileFastWithProcessors(toSourceFiles(files));
    }

    @Override
    public CompileTask compileFastWithProcessors(Collection<? extends JavaFileObject> sources) {
        var compile = compileBatch(sources, FAST_AP_PROFILE);
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps,
                isCachedBatch(compile) ? () -> {} : compile::close);
    }

    @Override
    public CompileTask compileFast(Collection<? extends JavaFileObject> sources) {
        var compile = compileBatch(sources, FAST_NO_AP_PROFILE);
        return new CompileTask(compile.task, compile.roots, diags, compile.sourceStamps,
                isCachedBatch(compile) ? () -> {} : compile::close);
    }

    CompileTelemetry lastCompileTelemetry() {
        return lastCompileTelemetry;
    }

    private List<JavaFileObject> toSourceFiles(Path... files) {
        var sources = new ArrayList<JavaFileObject>(files.length);
        for (var file : files) {
            sources.add(new SourceFileObject(file));
        }
        return sources;
    }

}
