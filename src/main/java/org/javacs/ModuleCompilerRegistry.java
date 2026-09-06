package org.javacs;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.WorkspaceTypeIndex;

/** Resolves build metadata and owns one analysis environment per module/source scope. */
final class ModuleCompilerRegistry implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("main");
    /** Maven spawns one process per module; keep the fan-out bounded on shared machines. */
    private static final int MAX_RESOLVER_THREADS = 4;
    private final JavaLanguageServer server;
    final Map<String, ModuleCompiler> moduleCompilers = new ConcurrentHashMap<>();
    private final Map<String, GradleTooling.ResolvedModule> gradleDependencies = new HashMap<>();
    InferConfig inferredConfig;
    boolean moduleScopedMaven;
    boolean moduleScopedGradle;
    Set<String> configuredAddExports = Set.of();
    List<String> configuredUserCompilerArgs = List.of();

    static final class ModuleCompiler implements AutoCloseable {
        final JavaCompilerService compiler;
        final ExternalBinaryTypeIndex externalIndex;
        final Set<Path> sourceRoots;
        private long indexVersion = -1;
        private WorkspaceTypeIndex workspaceIndex = WorkspaceTypeIndex.EMPTY;

        ModuleCompiler(JavaCompilerService compiler, Set<Path> sourceRoots) {
            this.compiler = compiler;
            this.externalIndex = new ExternalBinaryTypeIndex(compiler);
            this.sourceRoots = Set.copyOf(sourceRoots);
        }

        synchronized TypeIndexRouter typeIndex(JavaLanguageServer.CompletionSnapshot snapshot) {
            if (indexVersion != snapshot.version()) {
                workspaceIndex = snapshot.workspaceIndex().restrictTo(sourceRoots);
                indexVersion = snapshot.version();
            }
            return new TypeIndexRouter(workspaceIndex, externalIndex);
        }

        @Override public void close() {
            externalIndex.close();
            compiler.close();
        }
    }

    ModuleCompilerRegistry(JavaLanguageServer server) { this.server = server; }

    void clear() {
        close();
        moduleScopedMaven = false;
        moduleScopedGradle = false;
        inferredConfig = null;
    }

    @Override public void close() {
        moduleCompilers.values().forEach(ModuleCompiler::close);
        moduleCompilers.clear();
        gradleDependencies.clear();
    }

    String moduleCompilerKey(Path file) {
        var module = server.moduleGraph.moduleForFile(file).orElse(null);
        if (module == null) return "";
        return module.projectPath() + (module.isTestSource(file) ? "#test" : "#main");
    }

    synchronized JavaCompilerService compilerFor(Path file) {
        if (!moduleScopedMaven && !moduleScopedGradle) return server.getOrCreateCompiler();
        var module = server.moduleGraph.moduleForFile(file).orElse(null);
        if (module == null) return server.getOrCreateCompiler();
        var key = moduleCompilerKey(file);
        var existing = moduleCompilers.get(key);
        if (existing != null) return existing.compiler;
        var started = System.nanoTime();
        // A single-module project resolves once at startup; announcing it only adds UI noise.
        var token = multiModule() ? server.progress.begin("Resolving module", module.projectPath()) : null;
        try {
            var inputs = resolveInputs(module, module.isTestSource(file));
            includeSources(inputs.sourceRoots());
            var next = new JavaCompilerService(server.moduleGraph.externalClasspath(inputs.classpath()),
                    inputs.sources(), configuredAddExports, compilerArguments(module));
            next.setSourceRoots(inputs.sourceRoots());
            moduleCompilers.put(key, new ModuleCompiler(next, inputs.sourceRoots()));
            LOG.info("[module] ready id=" + key + " sources=" + inputs.sourceRoots().size()
                    + " dependencies=" + next.classPath.size() + " workspace_binaries=0 ms="
                    + (System.nanoTime() - started) / 1_000_000);
            return next;
        } finally {
            if (token != null) server.progress.end(token, "Module resolved");
        }
    }

    boolean multiModule() {
        return server.moduleGraph.modules().size() > 1;
    }

    private MavenTooling.MavenDependencies resolveInputs(ModuleGraph.ModuleInfo module, boolean test) {
        try {
            if (moduleScopedMaven) return inferredConfig.mavenModuleDependencies(module, test);
            var resolved = gradleDependencies.get(module.projectPath());
            if (resolved == null) {
                var targets = server.moduleGraph.transitiveModulePathsIncludingSelf(module.projectPath());
                targets.addAll(server.moduleGraph.transitiveModuleDependencies(module.projectPath(), test));
                gradleDependencies.putAll(GradleTooling.resolveClasspath(inferredConfig.buildRoot(),
                        targets.stream().distinct().toList()).modules());
                resolved = gradleDependencies.get(module.projectPath());
            }
            if (resolved != null) {
                // Dependency sources also need their own compile-only/implementation dependencies.
                var classpath = new LinkedHashSet<Path>(test ? resolved.testClasspath() : resolved.externalClasspath());
                for (var dependency : server.moduleGraph.transitiveModuleDependencies(module.projectPath(), test)) {
                    var inputs = gradleDependencies.get(dependency);
                    if (inputs != null) classpath.addAll(inputs.externalClasspath());
                }
                return new MavenTooling.MavenDependencies(classpath, Set.of(),
                        server.moduleGraph.transitiveSourceDirs(module.projectPath(), test));
            }
        } catch (RuntimeException failure) {
            LOG.warning("[module] partial_dependencies id=" + module.projectPath() + " cause=" + failure.getMessage());
        }
        return new MavenTooling.MavenDependencies(Set.of(), Set.of(),
                server.moduleGraph.transitiveSourceDirs(module.projectPath(), test));
    }

    private List<String> compilerArguments(ModuleGraph.ModuleInfo module) {
        var args = new ArrayList<>(configuredUserCompilerArgs);
        if (!JavaLanguageServer.hasExplicitJavaLevelOverride(args)) {
            if (!module.compilerArgs().isEmpty()) args.addAll(module.compilerArgs());
            else if (module.sourceCompatibility() != null) {
                args.add("--release");
                args.add(module.sourceCompatibility().replaceFirst("^1\\.", ""));
            }
        }
        return args;
    }

    void includeSources(Collection<Path> sources) {
        var expanded = new LinkedHashSet<>(FileStore.workspaceRoots());
        if (expanded.addAll(sources)) FileStore.setWorkspaceRoots(expanded);
    }

    void batchResolveModulesForFiles(Path[] files) {
        if (!moduleScopedGradle && !moduleScopedMaven) return;
        var selected = new LinkedHashMap<String, Path>();
        for (var file : files) selected.putIfAbsent(moduleCompilerKey(file), file);
        prefetchMavenMetadata(selected.values());
        selected.values().forEach(this::compilerFor);
    }

    /**
     * Warm the Maven metadata cache for every module a scan will touch. Each module needs one Maven
     * process, so they run concurrently; {@link #compilerFor} then reads the cache on the LSP thread.
     */
    private void prefetchMavenMetadata(Collection<Path> files) {
        if (!moduleScopedMaven || inferredConfig == null) return;
        var pending = new LinkedHashMap<String, ModuleGraph.ModuleInfo>();
        for (var file : files) {
            if (moduleCompilers.containsKey(moduleCompilerKey(file))) continue;
            // One resolution covers both scopes of a module, so key by module rather than by scope.
            server.moduleGraph.moduleForFile(file)
                    .ifPresent(module -> pending.putIfAbsent(module.projectPath(), module));
        }
        if (pending.size() < 2) return;
        var started = System.nanoTime();
        var token = server.progress.begin("Resolving modules", pending.size() + " modules");
        // Daemon threads so a hung resolver can never keep the server alive; close() awaits the work.
        try (var pool = Executors.newFixedThreadPool(Math.min(MAX_RESOLVER_THREADS, pending.size()),
                Thread.ofPlatform().daemon().name("jls-module-resolver-", 0).factory())) {
            for (var module : pending.values()) {
                pool.execute(() -> {
                    try {
                        inferredConfig.mavenModuleDependencies(module, false);
                    } catch (RuntimeException failure) {
                        LOG.warning("[module] prefetch_failed id=" + module.projectPath()
                                + " cause=" + failure.getMessage());
                    }
                });
            }
        } finally {
            server.progress.end(token, "Modules resolved");
            LOG.info("[module] prefetch modules=" + pending.size()
                    + " ms=" + (System.nanoTime() - started) / 1_000_000);
        }
    }

    TypeIndexRouter typeIndexFor(Path file) {
        var snapshot = server.completionSnapshotRef.get();
        var context = moduleCompilers.get(moduleCompilerKey(file));
        return context == null ? snapshot.typeIndex() : context.typeIndex(snapshot);
    }

    ExternalBinaryTypeIndex externalIndexForIndexing(Path file, JavaCompilerService parsingCompiler) {
        if (!moduleScopedMaven && !moduleScopedGradle) return server.completionSnapshotRef.get().externalIndex();
        var context = moduleCompilers.get(moduleCompilerKey(file));
        if (context != null) return context.externalIndex;
        for (var candidate : moduleCompilers.values()) {
            if (candidate.compiler == parsingCompiler && candidate.sourceRoots.stream().anyMatch(file::startsWith)) {
                return candidate.externalIndex;
            }
        }
        return ExternalBinaryTypeIndex.EMPTY;
    }

    CompilerProvider compilerForClass(String className) {
        var declaration = server.getOrCreateCompiler().findTypeDeclaration(className);
        return declaration == CompilerProvider.NOT_FOUND ? server.getOrCreateCompiler() : compilerFor(declaration);
    }

    void includeReferenceSources() {
        for (var module : server.moduleGraph.modules().values()) includeSources(module.sourceDirs());
    }

    boolean canReferenceModule(Path declaration, Path candidate) {
        if ((!moduleScopedMaven && !moduleScopedGradle) || declaration == null || candidate == null
                || declaration == CompilerProvider.NOT_FOUND) return true;
        var owner = server.moduleGraph.moduleForFile(declaration).orElse(null);
        var consumer = server.moduleGraph.moduleForFile(candidate).orElse(null);
        if (owner == null || consumer == null || owner.projectPath().equals(consumer.projectPath())) return true;
        var context = moduleCompilers.get(moduleCompilerKey(candidate));
        return context != null ? context.sourceRoots.stream().anyMatch(declaration::startsWith)
                : server.moduleGraph.transitiveModuleDependencies(consumer.projectPath(), consumer.isTestSource(candidate))
                        .contains(owner.projectPath());
    }
}
