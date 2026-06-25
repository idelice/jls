package org.javacs;

import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

class BuildManager {
    private static final Logger LOG = Logger.getLogger("main");

    public enum System { MAVEN, GRADLE, BAZEL, UNKNOWN }

    private final System system;
    private final Set<Path> outputDirs;
    private final Path buildRoot;
    private final JavaCompilerService compiler;

    private BuildManager(System system, Set<Path> outputDirs,
                         Path buildRoot, JavaCompilerService compiler) {
        this.system = system;
        this.outputDirs = Collections.unmodifiableSet(outputDirs);
        this.buildRoot = buildRoot;
        this.compiler = compiler;
    }

    public static BuildManager create(System system, Set<Path> outputDirs,
                                       Path buildRoot, JavaCompilerService compiler) {
        return new BuildManager(system, outputDirs, buildRoot, compiler);
    }

    public System system() {
        return system;
    }

    public Set<Path> outputDirs() {
        return outputDirs;
    }

    public Path buildRoot() { 
        return buildRoot;
    }

    /** True if at least one output directory exists and contains .class subdirectories. */
    public boolean isOutputAvailable() {
        for (var dir : outputDirs) {
            if (Files.isDirectory(dir)) {
                try (var entries = Files.list(dir)) {
                    if (entries.anyMatch(Files::isDirectory)) return true;
                } catch (java.io.IOException ignored) {}
            }
        }
        return false;
    }

    public Path outputDirFor(Path sourceFile) {
        if (outputDirs.size() == 1) return outputDirs.iterator().next();
        for (var dir : outputDirs) {
            if (sourceFile.startsWith(dir.getParent().resolve("src"))) return dir;
        }
        return outputDirs.isEmpty() ? null : outputDirs.iterator().next();
    }

    /** Full compile of all workspace sources with annotation processing.
     *  Writes .class files to output directories. Call once at startup. */
    public void build() {
        if (!compiler.lombokPresentOnClasspath()) return;
        if (outputDirs.isEmpty()) {
            LOG.warning("[build] BuildManager.build() skipped — no output directories found. "
                      + "Run project build manually for full Lombok support.");
            return;
        }
        compiler.fullCompileWithAP();
    }

    /** Incremental recompile of a single file with annotation processing.
     *  Updates the .class file in the appropriate output directory. */
    public void refresh(Path file) {
        if (!compiler.lombokPresentOnClasspath()) return;
        var outputDir = outputDirFor(file);
        if (outputDir == null) {
            LOG.warning("[build] BuildManager.refresh(" + file.getFileName()
                      + ") skipped — no output directory for this file.");
            return;
        }
        compiler.refreshBuildOutput(file);
    }
}
