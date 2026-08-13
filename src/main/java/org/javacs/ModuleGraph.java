package org.javacs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Build-system-agnostic module graph for multi-module projects.
 * Populated by {@link GradleTooling} or {@link MavenTooling}.
 */
public record ModuleGraph(Map<String, ModuleInfo> modules) {

    public static final ModuleGraph EMPTY = new ModuleGraph(Map.of());

    public record ModuleInfo(
            String projectPath,
            Path projectDir,
            List<Path> sourceDirs,
            Path testSourceDir,
            Path mainOutputDir,
            Path testOutputDir,
            List<Path> externalClasspath,
            List<String> moduleDeps,
            List<String> testModuleDeps,
            String sourceCompatibility,
            List<String> compilerArgs,
            String artifactId,
            String coordinates) {}

    /** Return the module that contains {@code file}, or empty. */
    public Optional<ModuleInfo> moduleForFile(Path file) {
        ModuleInfo best = null;
        for (var info : modules.values()) {
            if (file.startsWith(info.projectDir())) {
                if (best == null || info.projectDir().getNameCount() > best.projectDir().getNameCount()) {
                    best = info;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Return the union of external JARs for {@code projectPath} and all transitive inter-module deps. */
    public Set<Path> transitiveClasspath(String projectPath) {
        var result = new LinkedHashSet<Path>();
        var visited = new HashSet<String>();
        collectClasspath(projectPath, result, visited);
        for (var dir : transitiveClassOutputDirs(projectPath)) {
            if (Files.exists(dir)) result.add(dir);
        }
        return result;
    }

    /** Return the main class output directory for each transitive inter-module dependency. */
    public Set<Path> transitiveClassOutputDirs(String projectPath) {
        return transitiveClassOutputDirs(projectPath, false);
    }

    public Set<Path> transitiveClassOutputDirs(String projectPath, boolean testSources) {
        var result = new LinkedHashSet<Path>();
        var visited = new HashSet<String>();
        collectClassOutputDirs(projectPath, result, visited, testSources);
        return result;
    }

    /** Return transitive dependency modules, excluding the selected module. */
    public Set<String> transitiveModuleDependencies(String projectPath, boolean testSources) {
        var result = new LinkedHashSet<String>();
        var module = modules.get(projectPath);
        if (module == null) return result;
        var dependencies = testSources ? module.testModuleDeps() : module.moduleDeps();
        for (var dependency : dependencies) collectModuleDependencies(dependency, result, testSources);
        return result;
    }

    /** Return all source directories reachable from {@code projectPath} (including transitive deps). */
    public Set<Path> transitiveSourceDirs(String projectPath, boolean includeModuleTests) {
        var result = new LinkedHashSet<Path>();
        var visited = new HashSet<String>();
        collectSourceDirs(projectPath, result, visited, includeModuleTests);
        return result;
    }

    private void collectClassOutputDirs(
            String projectPath, Set<Path> result, Set<String> visited, boolean testSources) {
        if (!visited.add(projectPath)) return;
        var info = modules.get(projectPath);
        if (info == null) return;
        if (info.mainOutputDir() != null) result.add(info.mainOutputDir());
        var dependencies = testSources ? info.testModuleDeps() : info.moduleDeps();
        for (var dep : dependencies) {
            if (modules.containsKey(dep)) collectClassOutputDirs(dep, result, visited, false);
        }
    }

    private void collectModuleDependencies(String projectPath, Set<String> result, boolean testSources) {
        if (!result.add(projectPath)) return;
        var info = modules.get(projectPath);
        if (info == null) return;
        var dependencies = testSources ? info.testModuleDeps() : info.moduleDeps();
        for (var dependency : dependencies) collectModuleDependencies(dependency, result, false);
    }

    private void collectClasspath(String projectPath, Set<Path> result, Set<String> visited) {
        if (!visited.add(projectPath)) return;
        var info = modules.get(projectPath);
        if (info == null) return;
        result.addAll(info.externalClasspath());
        for (var dep : info.moduleDeps()) {
            collectClasspath(dep, result, visited);
        }
    }

    private void collectSourceDirs(
            String projectPath, Set<Path> result, Set<String> visited, boolean includeTests) {
        if (!visited.add(projectPath)) return;
        var info = modules.get(projectPath);
        if (info == null) return;
        for (var sourceDir : info.sourceDirs()) {
            if (includeTests || !sourceDir.equals(info.testSourceDir())) result.add(sourceDir);
        }
        var dependencies = includeTests ? info.testModuleDeps() : info.moduleDeps();
        for (var dep : dependencies) {
            collectSourceDirs(dep, result, visited, false);
        }
    }
}
