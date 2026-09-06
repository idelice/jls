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
            List<Path> testSourceDirs,
            Path mainOutputDir,
            Path testOutputDir,
            List<Path> externalClasspath,
            List<String> moduleDeps,
            List<String> testModuleDeps,
            String sourceCompatibility,
            List<String> compilerArgs,
            String artifactId,
            String coordinates) {
        public boolean isTestSource(Path file) { return testSourceDirs.stream().anyMatch(file::startsWith); }

        public List<Path> sources(boolean includeTests) {
            return includeTests ? sourceDirs : sourceDirs.stream().filter(dir -> !testSourceDirs.contains(dir)).toList();
        }
    }

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

    /** Build outputs describe ownership only; they are never compiler inputs. */
    public Set<Path> externalClasspath(Collection<Path> candidates) {
        var result = new LinkedHashSet<Path>();
        for (var candidate : candidates) {
            var path = candidate.toAbsolutePath().normalize();
            if (Files.exists(path) && modules.values().stream().noneMatch(module -> ownsBinary(module, path))) {
                result.add(path);
            }
        }
        return Set.copyOf(result);
    }

    private boolean ownsBinary(ModuleInfo module, Path path) {
        if (module.mainOutputDir() != null && path.startsWith(module.mainOutputDir())) return true;
        if (module.testOutputDir() != null && path.startsWith(module.testOutputDir())) return true;
        // Packaged reactor artifacts and their local-repository copies also belong to sources.
        if (path.startsWith(module.projectDir()) && path.toString().endsWith(".jar")) {
            if (path.startsWith(module.projectDir().resolve("target"))
                    || path.startsWith(module.projectDir().resolve("build"))) return true;
        }
        if (module.coordinates() == null) return false;
        var coordinates = module.coordinates().split(":");
        if (coordinates.length != 3) return false;
        var base = coordinates[1] + "-" + coordinates[2];
        var name = path.getFileName().toString();
        if (!name.equals(base + ".jar") && !name.equals(base + "-tests.jar")) return false;
        var repositoryPath = Path.of(coordinates[0].replace('.', '/'), coordinates[1], coordinates[2], name);
        return path.endsWith(repositoryPath);
    }

    /** Collect transitive module project paths for a given module, including itself. */
    public List<String> transitiveModulePathsIncludingSelf(String projectPath) {
        var result = new ArrayList<String>();
        result.add(projectPath);
        result.addAll(transitiveModuleDependencies(projectPath, false));
        return result;
    }

    /** Return transitive dependency modules, excluding the selected module. */
    public Set<String> transitiveModuleDependencies(String projectPath, boolean testSources) {
        var result = new LinkedHashSet<String>();
        var module = modules.get(projectPath);
        if (module == null) return result;
        var dependencies = testSources ? module.testModuleDeps() : module.moduleDeps();
        for (var dependency : dependencies) collectModuleDependencies(dependency, result, false);
        return result;
    }

    /** Return all source directories reachable from {@code projectPath} (including transitive deps). */
    public Set<Path> transitiveSourceDirs(String projectPath, boolean includeModuleTests) {
        var result = new LinkedHashSet<Path>();
        var visited = new HashSet<String>();
        collectSourceDirs(projectPath, result, visited, includeModuleTests);
        return result;
    }

    private void collectModuleDependencies(String projectPath, Set<String> result, boolean testSources) {
        if (!result.add(projectPath)) return;
        var info = modules.get(projectPath);
        if (info == null) return;
        var dependencies = testSources ? info.testModuleDeps() : info.moduleDeps();
        for (var dependency : dependencies) collectModuleDependencies(dependency, result, false);
    }

    private void collectSourceDirs(
            String projectPath, Set<Path> result, Set<String> visited, boolean includeTests) {
        if (!visited.add(projectPath)) return;
        var info = modules.get(projectPath);
        if (info == null) return;
        result.addAll(info.sources(includeTests));
        var dependencies = includeTests ? info.testModuleDeps() : info.moduleDeps();
        for (var dep : dependencies) {
            collectSourceDirs(dep, result, visited, false);
        }
    }
}
