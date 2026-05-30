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

    final JavaCompilerService parent;
    final ReusableCompiler.SlotContext slot;
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
            ReusableCompiler.SlotContext slot,
            List<String> options) {
        this.parent = parent;
        this.slot = slot;
        this.task =
                slot == null
                        ? parent.compiler.createSingleUseTask(parent.fileManager, parent.diags::add, options, files)
                        : parent.compiler.createTask(slot, parent.fileManager, parent.diags::add, options, files);
        this.borrow = new ReusableCompiler.Borrow(parent.compiler, slot, task);
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.types = task.getTypes();
        this.roots = new ArrayList<>();
        this.sourceStamps = collectSourceStamps(files);
        this.analysisMode = analysisMode;
        this.annotationProcessingEnabled = allowAP;

        long parseNanos = 0;
        long enterNanos = 0;
        long analyzeNanos = 0;

        try {
            var parseStarted = System.nanoTime();
            for (var t : task.parse()) {
                roots.add(t);
            }
            parseNanos = System.nanoTime() - parseStarted;

            var analyzeStarted = System.nanoTime();
            task.analyze();
            analyzeNanos = System.nanoTime() - analyzeStarted;
            if (analysisMode == AnalysisMode.FULL) {
                ANALYZE_INVOCATIONS.incrementAndGet();
            }
        } catch (IOException e) {
            try {
                borrow.close();
            } catch (Exception closeError) {
                LOG.fine("Failed to release task after IOException: " + closeError.getMessage());
            }
            throw new RuntimeException(e);
        } catch (Throwable e) {
            // Always try to release the task, but don't let cleanup errors mask the original error
            try {
                borrow.close();
            } catch (Exception closeError) {
                LOG.fine("Failed to release task after compilation error: " + closeError.getMessage());
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
            result.put(path, new CompileTask.SourceStamp(file.getLastModified(), FileStore.contentHash(path)));
        }
        return result;
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
        if (closed) return;
        borrow.close();
        closed = true;
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream()
                .map(Path::toString)
                .sorted()
                .collect(Collectors.joining(File.pathSeparator));
    }

    static List<String> options(
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

    static List<String> optionsForFastAp(
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
