package org.javacs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.WorkspaceTypeIndex;

/**
 * Manages per-module compiler instances for multi-module Maven and Gradle projects.
 * Handles module resolution, classpath setup, and compiler lifecycle.
 */
final class ModuleCompilerRegistry {
    private static final Logger LOG = Logger.getLogger("main");

    private final JavaLanguageServer server;

    static final class ModuleCompiler {
        final JavaCompilerService compiler;
        final ExternalBinaryTypeIndex externalIndex;
        final Set<Path> sourceRoots;
        long indexVersion = -1;
        WorkspaceTypeIndex workspaceIndex = WorkspaceTypeIndex.EMPTY;

        ModuleCompiler(
                JavaCompilerService compiler,
                ExternalBinaryTypeIndex externalIndex,
                Set<Path> sourceRoots) {
            this.compiler = compiler;
            this.externalIndex = externalIndex;
            this.sourceRoots = sourceRoots;
        }

        synchronized TypeIndexRouter typeIndex(JavaLanguageServer.CompletionSnapshot snapshot) {
            if (indexVersion != snapshot.version()) {
                workspaceIndex = snapshot.workspaceIndex().restrictTo(sourceRoots);
                indexVersion = snapshot.version();
            }
            return new TypeIndexRouter(workspaceIndex, externalIndex);
        }
    }

    final Map<String, ModuleCompiler> mavenModuleCompilers = new ConcurrentHashMap<>();
    final Map<String, ModuleCompiler> gradleModuleCompilers = new ConcurrentHashMap<>();
    /** Tracks which Gradle module project paths have had their classpath resolved. */
    final Set<String> resolvedGradleModules = ConcurrentHashMap.newKeySet();
    /** Tracks Maven module keys whose dependency resolution failed — avoids repeated attempts. */
    final Set<String> failedMavenModules = ConcurrentHashMap.newKeySet();
    InferConfig inferredConfig;
    boolean moduleScopedMaven;
    boolean moduleScopedGradle;
    Set<String> configuredAddExports = Set.of();
    List<String> configuredUserCompilerArgs = List.of();

    ModuleCompilerRegistry(JavaLanguageServer server) {
        this.server = server;
    }

    /** Clear all module state — called when compilers are recreated. */
    void clear() {
        mavenModuleCompilers.clear();
        failedMavenModules.clear();
        gradleModuleCompilers.clear();
        resolvedGradleModules.clear();
        moduleScopedMaven = false;
        moduleScopedGradle = false;
        inferredConfig = null;
    }

    String moduleCompilerKey(Path file) {
        var module = server.moduleGraph.moduleForFile(file).orElse(null);
        if (module == null) return "";
        var test = module.testSourceDir() != null && file.startsWith(module.testSourceDir());
        var prefix = moduleScopedGradle ? "gradle:" : "";
        return prefix + module.projectPath() + (test ? "#test" : "#main");
    }

    synchronized JavaCompilerService compilerFor(Path file) {
        if (!moduleScopedMaven && !moduleScopedGradle) return server.getOrCreateCompiler();
        if (moduleScopedGradle) return compilerForGradleModule(file);
        var module = server.moduleGraph.moduleForFile(file).orElse(null);
        if (module == null) return server.getOrCreateCompiler();
        var key = moduleCompilerKey(file);
        var existing = mavenModuleCompilers.get(key);
        if (existing != null) return existing.compiler;
        if (failedMavenModules.contains(key)) {
            throw new RuntimeException("Maven dependency resolution previously failed for " + module.projectPath());
        }
        var test = key.endsWith("#test");
        var started = Instant.now();
        var progressToken = server.progress.begin(
                "Resolving module",
                "Resolving " + module.projectPath());
        MavenTooling.MavenDependencies dependencies;
        try {
            dependencies = inferredConfig.mavenModuleDependencies(module, test);
        } catch (RuntimeException e) {
            server.progress.end(progressToken, "Resolution failed");
            failedMavenModules.add(key);
            LOG.warning(String.format(
                    "[maven] compiler_failed module=%s scope=%s reason=%s took=%dms",
                    module.projectPath(), test ? "test" : "main", e.getMessage(),
                    Duration.between(started, Instant.now()).toMillis()));
            throw e;
        }
        server.progress.end(progressToken, "Dependencies resolved");
        var args = new ArrayList<>(configuredUserCompilerArgs);
        if (!JavaLanguageServer.hasExplicitJavaLevelOverride(args)) args.addAll(module.compilerArgs());
        var next = new JavaCompilerService(
                dependencies.classpath(), dependencies.sources(), configuredAddExports, args);
        next.setModuleGraph(server.moduleGraph);
        var sourceRoots = dependencies.sourceRoots();
        next.setSourceRoots(sourceRoots);
        mavenModuleCompilers.put(
                key, new ModuleCompiler(next, new ExternalBinaryTypeIndex(next), sourceRoots));
        if (FileStore.activeDocuments().contains(file)) {
            server.completionIndexScheduler.scheduleRefresh(
                    List.of(file),
                    "moduleCompilerReady",
                    0,
                    JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
        }
        LOG.info(String.format(
                "[maven] compiler_ready module=%s scope=%s classpath=%d source_roots=%d took=%dms",
                module.projectPath(), test ? "test" : "main", dependencies.classpath().size(),
                sourceRoots.size(), Duration.between(started, Instant.now()).toMillis()));
        return next;
    }

    /**
     * Resolve and return a per-module compiler for a Gradle multi-module project.
     * If the module is not yet resolved, resolves classpath synchronously.
     */
    private JavaCompilerService compilerForGradleModule(Path file) {
        var module = server.moduleGraph.moduleForFile(file).orElse(null);
        if (module == null) return server.getOrCreateCompiler();
        var key = moduleCompilerKey(file);
        var existing = gradleModuleCompilers.get(key);
        if (existing != null) return existing.compiler;
        resolveGradleModule(module);
        var resolved = gradleModuleCompilers.get(key);
        return resolved != null ? resolved.compiler : server.getOrCreateCompiler();
    }

    /**
     * Resolve classpath for a Gradle module and create its compiler.
     * No-op if already resolved.
     */
    void resolveGradleModule(ModuleGraph.ModuleInfo module) {
        if (resolvedGradleModules.contains(module.projectPath())) return;
        var started = Instant.now();
        var progressToken = server.progress.begin(
                "Resolving module",
                "Resolving classpath for " + module.projectPath());
        try {
            var targets = server.moduleGraph.transitiveModulePathsIncludingSelf(module.projectPath());
            var resolved = GradleTooling.resolveClasspath(server.workspaceRoot, targets);
            if (resolved == GradleTooling.ModuleClasspath.EMPTY || resolved.modules().isEmpty()) {
                LOG.warning("[gradle] classpath resolution returned empty for " + module.projectPath());
                resolvedGradleModules.add(module.projectPath());
                return;
            }
            for (var entry : resolved.modules().entrySet()) {
                var modulePath = entry.getKey();
                var moduleClasspath = entry.getValue();
                var moduleInfo = server.moduleGraph.modules().get(modulePath);
                if (moduleInfo == null) continue;
                resolvedGradleModules.add(modulePath);

                var classpath = new LinkedHashSet<Path>(moduleClasspath.externalClasspath());
                classpath.addAll(server.moduleGraph.transitiveClassOutputDirs(modulePath));

                var args = new ArrayList<>(configuredUserCompilerArgs);
                if (!JavaLanguageServer.hasExplicitJavaLevelOverride(args)
                        && moduleInfo.sourceCompatibility() != null
                        && !moduleInfo.sourceCompatibility().isBlank()) {
                    args.add("--release");
                    args.add(moduleInfo.sourceCompatibility());
                }
                var compilerService = new JavaCompilerService(
                        classpath, Set.of(), configuredAddExports, args);
                compilerService.setModuleGraph(server.moduleGraph);
                var sourceRoots = new LinkedHashSet<>(moduleInfo.sourceDirs());
                compilerService.setSourceRoots(sourceRoots);

                var mainKey = "gradle:" + modulePath + "#main";
                gradleModuleCompilers.putIfAbsent(
                        mainKey, new ModuleCompiler(compilerService, new ExternalBinaryTypeIndex(compilerService), sourceRoots));

                if (!moduleClasspath.testClasspath().isEmpty()) {
                    var testClasspath = new LinkedHashSet<Path>(moduleClasspath.testClasspath());
                    testClasspath.addAll(server.moduleGraph.transitiveClassOutputDirs(modulePath, true));
                    var testArgs = new ArrayList<>(args);
                    var testCompiler = new JavaCompilerService(
                            testClasspath, Set.of(), configuredAddExports, testArgs);
                    testCompiler.setModuleGraph(server.moduleGraph);
                    var testKey = "gradle:" + modulePath + "#test";
                    gradleModuleCompilers.putIfAbsent(
                            testKey, new ModuleCompiler(testCompiler, new ExternalBinaryTypeIndex(testCompiler), sourceRoots));
                }

                LOG.info(String.format(
                        "[gradle] compiler_ready module=%s classpath=%d source_roots=%d took=%dms",
                        modulePath, classpath.size(), sourceRoots.size(),
                        Duration.between(started, Instant.now()).toMillis()));
            }
        } catch (Exception e) {
            LOG.warning("[gradle] module resolution failed for " + module.projectPath() + ": " + e.getMessage());
            resolvedGradleModules.add(module.projectPath());
        } finally {
            server.progress.end(progressToken, "Module resolved");
        }
    }

    /**
     * Batch pre-resolve modules for a set of candidate files before reference scanning.
     * Works for both Gradle and Maven multi-module. No-op for single-module projects.
     */
    void batchResolveModulesForFiles(Path[] files) {
        if (!moduleScopedGradle && !moduleScopedMaven) return;
        if (server.moduleGraph == ModuleGraph.EMPTY) return;

        if (moduleScopedGradle) batchResolveGradleModules(files);
        if (moduleScopedMaven) batchResolveMavenModules(files);
    }

    private void batchResolveGradleModules(Path[] files) {
        var unresolved = new LinkedHashSet<String>();
        for (var f : files) {
            var module = server.moduleGraph.moduleForFile(f).orElse(null);
            if (module != null && !resolvedGradleModules.contains(module.projectPath())) {
                unresolved.add(module.projectPath());
            }
        }
        if (unresolved.isEmpty()) return;
        var allTargets = new ArrayList<String>();
        for (var modulePath : unresolved) {
            for (var target : server.moduleGraph.transitiveModulePathsIncludingSelf(modulePath)) {
                if (!resolvedGradleModules.contains(target)) allTargets.add(target);
            }
        }
        if (allTargets.isEmpty()) return;
        LOG.info("[gradle] batch pre-resolving " + unresolved.size() + " modules for reference scan");
        try {
            GradleTooling.resolveClasspath(server.workspaceRoot, allTargets);
            for (var modulePath : unresolved) {
                var module = server.moduleGraph.modules().get(modulePath);
                if (module != null) resolveGradleModule(module);
            }
        } catch (Exception e) {
            LOG.warning("[gradle] batch pre-resolution failed: " + e.getMessage());
        }
    }

    private void batchResolveMavenModules(Path[] files) {
        var unresolvedModules = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        var unresolvedFiles = new LinkedHashMap<String, Path>();
        for (var f : files) {
            var module = server.moduleGraph.moduleForFile(f).orElse(null);
            if (module == null) continue;
            var key = moduleCompilerKey(f);
            if (key.isEmpty()) continue;
            if (mavenModuleCompilers.containsKey(key)) continue;
            if (failedMavenModules.contains(key)) continue;
            if (!unresolvedModules.containsKey(key)) {
                unresolvedModules.put(key, module);
                unresolvedFiles.put(key, f);
            }
        }
        if (unresolvedModules.isEmpty()) return;
        LOG.info("[maven] batch pre-resolving " + unresolvedModules.size()
                + " modules for reference scan");

        var futures = new LinkedHashMap<String, CompletableFuture<MavenTooling.MavenDependencies>>();
        for (var entry : unresolvedModules.entrySet()) {
            var key = entry.getKey();
            var module = entry.getValue();
            var test = key.endsWith("#test");
            futures.put(key, CompletableFuture.supplyAsync(() ->
                    inferredConfig.mavenModuleDependencies(module, test)));
        }

        for (var entry : futures.entrySet()) {
            var key = entry.getKey();
            var module = unresolvedModules.get(key);
            var test = key.endsWith("#test");
            var file = unresolvedFiles.get(key);
            try {
                var dependencies = entry.getValue().join();
                synchronized (this) {
                    if (mavenModuleCompilers.containsKey(key)) continue;
                    var args = new ArrayList<>(configuredUserCompilerArgs);
                    if (!JavaLanguageServer.hasExplicitJavaLevelOverride(args)) args.addAll(module.compilerArgs());
                    var compiler = new JavaCompilerService(
                            dependencies.classpath(), dependencies.sources(),
                            configuredAddExports, args);
                    compiler.setModuleGraph(server.moduleGraph);
                    var sourceRoots = dependencies.sourceRoots();
                    compiler.setSourceRoots(sourceRoots);
                    mavenModuleCompilers.put(key,
                            new ModuleCompiler(compiler, new ExternalBinaryTypeIndex(compiler),
                                    sourceRoots));
                    if (FileStore.activeDocuments().contains(file)) {
                        server.completionIndexScheduler.scheduleRefresh(
                                List.of(file), "moduleCompilerReady", 0,
                                JavaLanguageServer.CompletionIndexRefreshMode.WORKSPACE_DECLARATION_MERGE);
                    }
                }
                LOG.info("[maven] batch resolved " + module.projectPath()
                        + " scope=" + (test ? "test" : "main"));
            } catch (Exception e) {
                failedMavenModules.add(key);
                LOG.warning("[maven] batch resolution failed for "
                        + module.projectPath() + ": " + e.getMessage());
            }
        }
    }

    TypeIndexRouter typeIndexFor(Path file) {
        var snapshot = server.completionSnapshotRef.get();
        if (!moduleScopedMaven && !moduleScopedGradle) return snapshot.typeIndex();
        var key = moduleCompilerKey(file);
        if (moduleScopedGradle) {
            var context = gradleModuleCompilers.get(key);
            return context == null ? snapshot.typeIndex() : context.typeIndex(snapshot);
        }
        var context = mavenModuleCompilers.get(key);
        return context == null ? snapshot.typeIndex() : context.typeIndex(snapshot);
    }

    ExternalBinaryTypeIndex externalIndexForIndexing(
            Path file, JavaCompilerService parsingCompiler) {
        if (!moduleScopedMaven && !moduleScopedGradle) return server.completionSnapshotRef.get().externalIndex();
        if (moduleScopedGradle) {
            var context = gradleModuleCompilers.get(moduleCompilerKey(file));
            if (context != null) return context.externalIndex;
            for (var candidate : gradleModuleCompilers.values()) {
                if (candidate.compiler == parsingCompiler
                        && candidate.sourceRoots.stream().anyMatch(file::startsWith)) {
                    return candidate.externalIndex;
                }
            }
            return ExternalBinaryTypeIndex.EMPTY;
        }
        var context = mavenModuleCompilers.get(moduleCompilerKey(file));
        if (context != null) return context.externalIndex;
        for (var candidate : mavenModuleCompilers.values()) {
            if (candidate.compiler == parsingCompiler
                    && candidate.sourceRoots.stream().anyMatch(file::startsWith)) {
                return candidate.externalIndex;
            }
        }
        return ExternalBinaryTypeIndex.EMPTY;
    }

    CompilerProvider compilerForClass(String className) {
        for (var context : mavenModuleCompilers.values()) {
            if (context.compiler.findTypeDeclaration(className) != CompilerProvider.NOT_FOUND) {
                return context.compiler;
            }
        }
        for (var context : gradleModuleCompilers.values()) {
            if (context.compiler.findTypeDeclaration(className) != CompilerProvider.NOT_FOUND) {
                return context.compiler;
            }
        }
        return server.getOrCreateCompiler();
    }

    void includeMavenReferenceSources() {
        if (!moduleScopedMaven && !moduleScopedGradle) return;
        var roots = new LinkedHashSet<>(FileStore.workspaceRoots());
        for (var module : server.moduleGraph.modules().values()) {
            for (var sourceDir : module.sourceDirs()) {
                if (Files.isDirectory(sourceDir)) roots.add(sourceDir);
            }
        }
        if (!roots.equals(FileStore.workspaceRoots())) FileStore.setWorkspaceRoots(roots);
    }

    boolean canReferenceModule(Path declaration, Path candidate) {
        if ((!moduleScopedMaven && !moduleScopedGradle) || declaration == null || candidate == null
                || declaration == CompilerProvider.NOT_FOUND) return true;
        var owner = server.moduleGraph.moduleForFile(declaration).orElse(null);
        var consumer = server.moduleGraph.moduleForFile(candidate).orElse(null);
        if (owner == null || consumer == null || owner.projectPath().equals(consumer.projectPath())) {
            return true;
        }
        var key = moduleCompilerKey(candidate);
        var context = moduleScopedGradle
                ? gradleModuleCompilers.get(key)
                : mavenModuleCompilers.get(key);
        if (context != null) {
            return context.sourceRoots.stream().anyMatch(declaration::startsWith);
        }
        var test = consumer.testSourceDir() != null && candidate.startsWith(consumer.testSourceDir());
        return server.moduleGraph.transitiveModuleDependencies(consumer.projectPath(), test)
                .contains(owner.projectPath());
    }
}
