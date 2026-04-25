package org.javacs;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Immutable, read-only metadata computed once per compiler-creation event and shared across all
 * three compiler lanes (interactive, background, index).
 *
 * <p>This holds configuration-derived values that are identical for all lanes and safe to share:
 * normalized paths, classpath/JDK type-index sets, lombok detection, and doc metadata. It
 * explicitly does <em>not</em> hold any mutable execution state (compilers, caches, file managers).
 */
record CompilerSharedResources(
        Set<Path> classPath,
        Set<Path> docPath,
        Set<String> addExports,
        List<String> extraArgs,
        Set<String> jdkClasses,
        Set<String> classPathClasses,
        boolean lombokPresentOnClasspath,
        Docs docs) {

    private static final Logger LOG = Logger.getLogger("main");

    /**
     * Build shared resources from raw configuration values. This is the single initialization path
     * that should be called once per compiler-recreation event, before constructing any lanes.
     */
    static CompilerSharedResources from(
            Set<Path> classPath,
            Set<Path> docPath,
            Set<String> addExports,
            Collection<String> extraArgs) {
        var started = Instant.now();

        var normalizedClassPath = Collections.unmodifiableSet(classPath);
        var normalizedDocPath = Collections.unmodifiableSet(docPath);
        var normalizedAddExports = Collections.unmodifiableSet(addExports);
        List<String> normalizedExtraArgs;
        if (extraArgs instanceof Set<?>) {
            var sorted = new ArrayList<>(extraArgs);
            Collections.sort(sorted);
            normalizedExtraArgs = List.copyOf(sorted);
        } else {
            normalizedExtraArgs = List.copyOf(extraArgs);
        }

        var docsStarted = Instant.now();
        var docs = new Docs(docPath);
        var docsReady = Instant.now();

        var jdkClasses = ScanClassPath.jdkTopLevelClasses();
        var classPathScanStarted = Instant.now();
        var classPathClasses = ScanClassPath.classPathTopLevelClasses(classPath);
        var classPathScanReady = Instant.now();

        var lombokPresentOnClasspath = classPath.stream()
                .anyMatch(p -> {
                    var name = p.getFileName().toString().toLowerCase();
                    return name.startsWith("lombok") && (name.endsWith(".jar") || name.endsWith("-all.jar"));
                });

        LOG.info(String.format(
                "[perf] shared_resources_init classpath=%d docpath=%d docs=%dms classpath_scan=%dms total=%dms lombok_present=%s",
                normalizedClassPath.size(),
                normalizedDocPath.size(),
                Duration.between(docsStarted, docsReady).toMillis(),
                Duration.between(classPathScanStarted, classPathScanReady).toMillis(),
                Duration.between(started, Instant.now()).toMillis(),
                lombokPresentOnClasspath));

        return new CompilerSharedResources(
                normalizedClassPath,
                normalizedDocPath,
                normalizedAddExports,
                normalizedExtraArgs,
                jdkClasses,
                classPathClasses,
                lombokPresentOnClasspath,
                docs);
    }
}
