package org.javacs;

import com.sun.source.util.JavacTask;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import javax.tools.*;

    /**
 * Immutable, read-only metadata computed once per compiler-creation event and shared across all
 * three compiler lanes (interactive, background, index).
 *
 * <p>This holds configuration-derived values that are identical for all lanes and safe to share:
 * normalized paths, classpath/JDK type-index sets, lombok detection, doc metadata,
 * and a shared per-file compile cache. It explicitly does <em>not</em> hold any mutable
 * execution state (compilers, per-lane workspace caches, file managers).
 */
record CompilerSharedResources(
        Set<Path> classPath,
        Set<Path> docPath,
        Set<String> addExports,
        List<String> extraArgs,
        Set<String> jdkClasses,
        Set<String> classPathClasses,
        boolean lombokPresentOnClasspath,
        boolean apEnabled,
        Docs docs,
        Cache<Boolean, CompileBatch> fileCache) {

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

        var apEnabled = lombokPresentOnClasspath
                && probeAnnotationProcessing(classPath, normalizedAddExports, normalizedExtraArgs);

        LOG.info(String.format(
                "[perf] shared_resources_init classpath=%d docpath=%d docs=%dms classpath_scan=%dms total=%dms lombok_present=%s ap_enabled=%s",
                normalizedClassPath.size(),
                normalizedDocPath.size(),
                Duration.between(docsStarted, docsReady).toMillis(),
                Duration.between(classPathScanStarted, classPathScanReady).toMillis(),
                Duration.between(started, Instant.now()).toMillis(),
                lombokPresentOnClasspath,
                apEnabled));

        return new CompilerSharedResources(
                normalizedClassPath,
                normalizedDocPath,
                normalizedAddExports,
                normalizedExtraArgs,
                jdkClasses,
                classPathClasses,
                lombokPresentOnClasspath,
                apEnabled,
                docs,
                new Cache<>("compile_file", 16));
    }

    /**
     * Probe whether Lombok's annotation processor is compatible with the running JDK.
     * Compiles a minimal class with AP enabled; returns false if Lombok's initialization crashes.
     * This runs once per compiler-creation event and sets {@code apEnabled} for all three lanes.
     */
    private static boolean probeAnnotationProcessing(
            Set<Path> classPath, Set<String> addExports, List<String> extraArgs) {
        var options = CompileBatch.options(classPath, addExports, extraArgs, true);
        var uri = URI.create("mem:///LombokProbe.java");
        var probeSource = new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return "class LombokProbe {}";
            }
        };
        try {
            var javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) {
                LOG.warning("[lombok] ap_probe_skipped reason=no_system_compiler ap_assumed_enabled");
                return true;
            }
            var rawTask = javac.getTask(null, null, null, options, null, List.of(probeSource));
            var task = (JavacTask) rawTask;
            for (var ignored : task.parse()) { /* consume parse results */ }
            for (var ignored : task.analyze()) { /* triggers AP initialization — throws if Lombok is incompatible */ }
            LOG.info("[lombok] ap_probe_passed ap_enabled=true");
            return true;
        } catch (Throwable e) {
            LOG.warning(String.format(
                    "[lombok] ap_probe_failed ap_enabled=false reason=%s cause=%s",
                    e.getMessage(), e.getClass().getName()));
            return false;
        }
    }
}
