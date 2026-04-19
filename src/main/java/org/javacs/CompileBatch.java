package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import javax.lang.model.util.*;
import javax.tools.*;

public class CompileBatch implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("main");
        private static final AtomicLong FULL_BATCHES = new AtomicLong();
    private static final AtomicLong ANALYZE_INVOCATIONS = new AtomicLong();
    private static final AtomicLong AP_ENABLED_BATCHES = new AtomicLong();

    enum AnalysisMode {
        ATTR,
        FULL
    }

    /**
     * Exception thrown when annotation processing fails.
     * Indicates that compilation should be retried without AP.
     */
    static class APFailureException extends RuntimeException {
        APFailureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final JavaCompilerService parent;
    final ReusableCompiler.Borrow borrow;
    /** Indicates the task that requested the compilation is finished with it. */
    boolean closed;

    final JavacTask task;
    final Trees trees;
    final Elements elements;
    final Types types;
    final List<CompilationUnitTree> roots;
    final Map<Path, CompileTask.SourceStamp> sourceStamps;
    final AnalysisMode analysisMode;
    final boolean annotationProcessingEnabled;
    final long parseMs;
    final long enterMs;
    final long analyzeMs;

    CompileBatch(
            JavaCompilerService parent,
            Collection<? extends JavaFileObject> files,
            boolean allowAP,
            AnalysisMode analysisMode,
            ReusableCompiler compiler) {
        this.parent = parent;
        this.borrow = batchTask(parent, files, allowAP, analysisMode, compiler);
        this.task = borrow.task;
        this.trees = Trees.instance(borrow.task);
        this.elements = borrow.task.getElements();
        this.types = borrow.task.getTypes();
        this.roots = new ArrayList<>();
        this.sourceStamps = collectSourceStamps(files);
        this.analysisMode = analysisMode;
        this.annotationProcessingEnabled = allowAP;

        long parseNanos = 0;
        long enterNanos = 0;
        long analyzeNanos = 0;

        try {
            var parseStarted = System.nanoTime();
            for (var t : borrow.task.parse()) {
                roots.add(t);
            }
            parseNanos = System.nanoTime() - parseStarted;

            if (allowAP) {
                // When AP is enabled, let javac drive enter+process+analyze as one pipeline.
                // Running enter() first can lock in pre-processor symbols (missing Lombok members).
                var analyzeStarted = System.nanoTime();
                borrow.task.analyze();
                analyzeNanos = System.nanoTime() - analyzeStarted;
                if (analysisMode == AnalysisMode.FULL) {
                    ANALYZE_INVOCATIONS.incrementAndGet();
                }
            } else {
                var enterStarted = System.nanoTime();
                invokeEnter(borrow.task);
                enterNanos = System.nanoTime() - enterStarted;

                // The results of borrow.task.analyze() are unreliable when errors are present
                // You can get at `Element` values using `Trees`
                var analyzeStarted = System.nanoTime();
                borrow.task.analyze();
                analyzeNanos = System.nanoTime() - analyzeStarted;
                if (analysisMode == AnalysisMode.FULL) {
                    ANALYZE_INVOCATIONS.incrementAndGet();
                }
            }
        } catch (IOException e) {
            try {
                borrow.close();
            } catch (Exception closeError) {
                LOG.fine("Failed to close borrow after IOException: " + closeError.getMessage());
            }
            throw new RuntimeException(e);
        } catch (Throwable e) {
            // Always try to close the borrow, but don't let cleanup errors mask the original error
            try {
                borrow.close();
            } catch (Exception closeError) {
                LOG.fine("Failed to close borrow after compilation error: " + closeError.getMessage());
            }

            // If AP was enabled and we got an error, it's likely an AP infrastructure bug
            // Catch both Exception and Error (like AssertionError from javac)
            if (allowAP && (isDeferredDiagnosticError(e) || isLikelyAPError(e))) {
                throw new APFailureException("Annotation processing failed: " + e.getMessage(), e);
            }

            // For other errors, wrap in RuntimeException
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Compilation failed: " + e.getMessage(), e);
        } finally {
            this.parseMs = parseNanos / 1_000_000;
            this.enterMs = enterNanos / 1_000_000;
            this.analyzeMs = analyzeNanos / 1_000_000;
            if (analysisMode == AnalysisMode.FULL) {
                FULL_BATCHES.incrementAndGet();
            }
            if (allowAP) {
                AP_ENABLED_BATCHES.incrementAndGet();
            }
            logPhaseTimings(files.size(), analysisMode, parseNanos, enterNanos, analyzeNanos);
        }
    }

    public static void resetPerfCounters() {
        FULL_BATCHES.set(0);
        ANALYZE_INVOCATIONS.set(0);
        AP_ENABLED_BATCHES.set(0);
    }

    public static PerfCounters perfCounters() {
        return new PerfCounters(
                FULL_BATCHES.get(),
                ANALYZE_INVOCATIONS.get(),
                AP_ENABLED_BATCHES.get());
    }

    public static class PerfCounters {
        public final long fullBatches;
        public final long analyzeInvocations;
        public final long apEnabledBatches;

        PerfCounters(
                long fullBatches,
                long analyzeInvocations,
                long apEnabledBatches) {
            this.fullBatches = fullBatches;
            this.analyzeInvocations = analyzeInvocations;
            this.apEnabledBatches = apEnabledBatches;
        }
    }

    private void logPhaseTimings(
            int sourceCount, AnalysisMode mode, long parseNanos, long enterNanos, long analyzeNanos) {
        LOG.fine(
                String.format(
                        "[perf] javac_phases mode=%s sources=%d parse=%dms enter=%dms analyze=%dms",
                        mode.name().toLowerCase(),
                        sourceCount,
                        parseNanos / 1_000_000,
                        enterNanos / 1_000_000,
                        analyzeNanos / 1_000_000));
    }

    private void invokeEnter(JavacTask task) {
        try {
            var enter = task.getClass().getMethod("enter");
            enter.invoke(task);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to run javac enter phase", e);
        }
    }

    private Map<Path, CompileTask.SourceStamp> collectSourceStamps(Collection<? extends JavaFileObject> files) {
        var result = new HashMap<Path, CompileTask.SourceStamp>();
        for (var file : files) {
            var uri = file.toUri();
            if (uri == null || !"file".equals(uri.getScheme())) continue;
            Path path;
            try {
                path = Paths.get(uri);
            } catch (RuntimeException ignored) {
                continue;
            }
            var version = -1;
            if (file instanceof SourceFileObject sourceFile) {
                version = sourceFile.contentVersion();
            }
            result.put(path, new CompileTask.SourceStamp(file.getLastModified(), version));
        }
        return result;
    }

    /**
     * Check if the error is related to javac's deferred diagnostic handler or other AP internal bugs.
     * These are failures in javac's internal annotation processing infrastructure.
     */
    private static boolean isDeferredDiagnosticError(Throwable e) {
        if (e == null) return false;

        // Check error message and all nested causes for AP infrastructure errors
        var current = e;
        while (current != null) {
            var message = current.getMessage();
            if (message != null) {
                // Match various AP/javac internal errors
                if (message.contains("DeferredDiagnosticHandler")
                    || message.contains("Log$")
                    || message.contains("deferredDiagnosticHandler")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Check if an error is likely caused by javac AP infrastructure bugs.
     * Errors in the AP infrastructure often come from:
     * - NullPointerExceptions in javac compiler classes
     * - AssertionErrors in javac AP code (processAnnotations, etc.)
     * - Errors wrapped by javac (e.g., IllegalStateException wrapping AssertionError)
     * - Errors in com.sun.tools.javac.* AP-related classes
     */
    private static boolean isLikelyAPError(Throwable e) {
        if (e == null) return false;

        // Check the exception and all its causes in the chain
        var current = e;
        while (current != null) {
            // AssertionError is often used in javac internal assertions and indicates a bug
            if (current instanceof AssertionError) {
                for (var frame : current.getStackTrace()) {
                    var className = frame.getClassName();
                    // AssertionError in javac AP code is definitely an AP bug
                    if (className.contains("com.sun.tools.javac")) {
                        return true;
                    }
                }
            }

            // Check the stack trace for javac AP infrastructure classes
            for (var frame : current.getStackTrace()) {
                var className = frame.getClassName();
                // Check if error originated in javac AP infrastructure
                if (className.contains("com.sun.tools.javac.comp.Annotate")
                    || className.contains("com.sun.tools.javac.util.Log")
                    || className.contains("com.sun.tools.javac.comp.Enter")
                    || className.contains("com.sun.tools.javac.processing")
                    || className.contains("com.sun.tools.javac.main.JavaCompiler.processAnnotations")) {
                    return true;
                }
            }

            // Also check for NullPointerException in any javac class
            if (current instanceof NullPointerException) {
                for (var frame : current.getStackTrace()) {
                    if (frame.getClassName().startsWith("com.sun.tools.javac")) {
                        return true;
                    }
                }
            }

            // Check next cause in chain
            current = current.getCause();
        }

        return false;
    }


    /**
     * If the compilation failed because javac didn't find some package-private files in source files with different
     * names, list those source files.
     */
    Set<Path> needsAdditionalSources() {
        // Check for "class not found errors" that refer to package private classes
        var addFiles = new HashSet<Path>();
        for (var err : parent.diags) {
            if (!err.getCode().equals("compiler.err.cant.resolve.location")) continue;
            if (!isValidFileRange(err)) continue;
            var className = errorText(err);
            var packageName = packageName(err);
            if (packageName != null) {
                var location = findPackagePrivateClass(packageName, className);
                if (location != FILE_NOT_FOUND) {
                    addFiles.add(location);
                }
            }
        }
        return addFiles;
    }

    private String errorText(javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> err) {
        var file = Paths.get(err.getSource().toUri());
        var contents = FileStore.contents(file);
        var begin = (int) err.getStartPosition();
        var end = (int) err.getEndPosition();
        return contents.substring(begin, end);
    }

    private String packageName(javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> err) {
        var file = Paths.get(err.getSource().toUri());
        return FileStore.packageName(file);
    }

    private static final Path FILE_NOT_FOUND = Paths.get("");

    private Path findPackagePrivateClass(String packageName, String className) {
        for (var file : FileStore.list(packageName)) {
            var parse = parent.parse(file);
            for (var declaration : parse.root().getTypeDecls()) {
                if (!(declaration instanceof ClassTree)) continue;
                var cls = (ClassTree) declaration;
                var isPublic = cls.getModifiers().getFlags().contains(Modifier.PUBLIC);
                if (isPublic) continue;
                if (cls.getSimpleName().contentEquals(className)) {
                    return file;
                }
            }
        }
        return FILE_NOT_FOUND;
    }

    @Override
    public void close() {
        borrow.close();
        closed = true;
    }

    private static ReusableCompiler.Borrow batchTask(
            JavaCompilerService parent,
            Collection<? extends JavaFileObject> sources,
            boolean allowAP,
            AnalysisMode mode,
            ReusableCompiler compiler) {
        parent.diags.clear();
        var options =
                allowAP
                        ? (mode == AnalysisMode.ATTR
                                ? optionsForFastAp(
                                        parent.classPath, parent.addExports, parent.extraArgs, parent.lombokPresentOnClasspath)
                                : options(parent.classPath, parent.addExports, parent.extraArgs, parent.lombokPresentOnClasspath))
                        : optionsWithoutAP(parent.classPath, parent.addExports, parent.extraArgs);
        return compiler.getTask(parent.fileManager, parent.diags::add, options, List.of(), sources);
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream()
                .map(Path::toString)
                .sorted()
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> options(
            Set<Path> classPath, Set<String> addExports, List<String> extraArgs, boolean lombokPresent) {
        var list = new ArrayList<String>();

        Collections.addAll(list, "-classpath", joinPath(classPath));
        if (!targetsJava8OrEarlier(extraArgs)) {
            Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        }
        // Collections.addAll(list, "-verbose");

        if (lombokPresent) {
            // Bind AP directly to Lombok to avoid processor discovery work on each diagnostics run.
            Collections.addAll(list, "-proc:full");
            Collections.addAll(list, "-processor", "lombok.launch.AnnotationProcessorHider$AnnotationProcessor");
            var processorPath = lombokProcessorPath(classPath);
            if (!processorPath.isEmpty()) {
                Collections.addAll(list, "-processorpath", processorPath);
            }
        } else {
            Collections.addAll(list, "-proc:none");
        }

        Collections.addAll(list, "-g");
        // You would think we could do -Xlint:all,
        // but some lints trigger fatal errors in the presence of parse errors
        Collections.addAll(
                list,
                "-Xlint:cast",
                "-Xlint:deprecation",
                "-Xlint:empty",
                "-Xlint:fallthrough",
                "-Xlint:finally",
                "-Xlint:path",
                "-Xlint:unchecked",
                "-Xlint:varargs",
                "-Xlint:static");
        list.addAll(extraArgs);
        var sortedExports = new ArrayList<>(addExports);
        Collections.sort(sortedExports);
        for (var export : sortedExports) {
            list.add("--add-exports");
            list.add(export + "=ALL-UNNAMED");
        }

        return list;
    }

    private static List<String> optionsForFastAp(
            Set<Path> classPath, Set<String> addExports, List<String> extraArgs, boolean lombokPresent) {
        var list = options(classPath, addExports, extraArgs, lombokPresent);
        if (lombokPresent) {
            // Run AP + attribution, but stop before FLOW so completion stays lightweight.
            Collections.addAll(list, "-XDshould-stop.ifNoError=ATTR", "-XDshould-stop.ifError=ATTR");
        }
        return list;
    }

    private static String lombokProcessorPath(Set<Path> classPath) {
        return classPath.stream()
                .filter(CompileBatch::isLombokJar)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static boolean isLombokJar(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("lombok") && (name.endsWith(".jar") || name.endsWith("-all.jar"));
    }

    /**
     * Create compilation options with annotation processing disabled.
     * Used for retrying compilation after AP failure.
     */
    static List<String> optionsWithoutAP(Set<Path> classPath, Set<String> addExports, List<String> extraArgs) {
        var list = new ArrayList<String>();
        Collections.addAll(list, "-classpath", joinPath(classPath));
        if (!targetsJava8OrEarlier(extraArgs)) {
            Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        }
        Collections.addAll(list, "-proc:none");
        Collections.addAll(list, "-g");
        Collections.addAll(
                list,
                "-Xlint:cast",
                "-Xlint:deprecation",
                "-Xlint:empty",
                "-Xlint:fallthrough",
                "-Xlint:finally",
                "-Xlint:path",
                "-Xlint:unchecked",
                "-Xlint:varargs",
                "-Xlint:static");
        list.addAll(extraArgs);
        var sortedExports = new ArrayList<>(addExports);
        Collections.sort(sortedExports);
        for (var export : sortedExports) {
            list.add("--add-exports");
            list.add(export + "=ALL-UNNAMED");
        }
        return list;
    }

    private static boolean targetsJava8OrEarlier(List<String> extraArgs) {
        for (int i = 0; i < extraArgs.size() - 1; i++) {
            var arg = extraArgs.get(i);
            if (!"--release".equals(arg) && !"-source".equals(arg) && !"-target".equals(arg)) {
                continue;
            }
            var level = parseJavaLevel(extraArgs.get(i + 1));
            if (level > 0) {
                return level <= 8;
            }
        }
        return false;
    }

    private static int parseJavaLevel(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        if (value.startsWith("1.")) {
            value = value.substring(2);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isValidFileRange(javax.tools.Diagnostic<? extends JavaFileObject> d) {
        return d.getSource().toUri().getScheme().equals("file") && d.getStartPosition() >= 0 && d.getEndPosition() >= 0;
    }
}
