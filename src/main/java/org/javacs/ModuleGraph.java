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
            List<Path> externalClasspath,
            List<String> moduleDeps,
            String sourceCompatibility) {}

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

    /** Return build/classes/java/main for each transitive inter-module dependency. */
    public Set<Path> transitiveClassOutputDirs(String projectPath) {
        var result = new LinkedHashSet<Path>();
        var visited = new HashSet<String>();
        collectClassOutputDirs(projectPath, result, visited);
        return result;
    }

    /** Return all source directories reachable from {@code projectPath} (including transitive deps). */
    public Set<Path> transitiveSourceDirs(String projectPath) {
        var result = new LinkedHashSet<Path>();
        var visited = new HashSet<String>();
        collectSourceDirs(projectPath, result, visited);
        return result;
    }

    private void collectClassOutputDirs(String projectPath, Set<Path> result, Set<String> visited) {
        if (!visited.add(projectPath)) return;
        var info = modules.get(projectPath);
        if (info == null) return;
        result.add(info.projectDir().resolve("build/classes/java/main"));
        for (var dep : info.moduleDeps()) {
            if (modules.containsKey(dep)) collectClassOutputDirs(dep, result, visited);
        }
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

    private void collectSourceDirs(String projectPath, Set<Path> result, Set<String> visited) {
        if (!visited.add(projectPath)) return;
        var info = modules.get(projectPath);
        if (info == null) return;
        result.addAll(info.sourceDirs());
        for (var dep : info.moduleDeps()) {
            collectSourceDirs(dep, result, visited);
        }
    }
}
