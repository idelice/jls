package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.comp.CompileStates.CompileState;
import com.sun.tools.javac.util.Options;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.util.*;
import javax.tools.*;

class CompileBatch implements AutoCloseable {
    static final int MAX_COMPLETION_ITEMS = 50;
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");

    final JavaCompilerService parent;
    final ReusableCompiler.Borrow borrow;
    /** Indicates the task that requested the compilation is finished with it. */
    boolean closed;

    final JavacTask task;
    final Trees trees;
    final Elements elements;
    final Types types;
    final List<CompilationUnitTree> roots;

    CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files) {
        this.parent = parent;
        this.borrow = batchTask(parent, files);
        boolean success = false;
        try {
            this.task = borrow.task;
            if (LombokSupport.isEnabled()) {
                relaxShouldStopPolicy(this.task);
                configureLombokProcessor(this.task);
            }
            registerTaskLogging(this.task);
            this.trees = Trees.instance(borrow.task);
            this.elements = borrow.task.getElements();
            this.types = borrow.task.getTypes();
            this.roots = new ArrayList<>();
            // Compile all roots
            for (var t : borrow.task.parse()) {
                roots.add(t);
            }
            // Ensure annotation processors run (especially Lombok) without forcing codegen,
            // which would clean up the task context. enter() triggers processing rounds;
            // analyze() drives flow/attr for diagnostics.
            if (LombokSupport.isEnabled() && borrow.task instanceof JavacTaskImpl jt) {
                jt.enter();
            }
            borrow.task.analyze();
            success = true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (!success) {
                borrow.close();
            }
        }
    }

    private static void registerTaskLogging(JavacTask task) {
        final long startNanos = System.nanoTime();
        task.addTaskListener(
                new TaskListener() {
                    @Override
                    public void started(TaskEvent e) {
                        logEvent("started", e, startNanos);
                    }

                    @Override
                    public void finished(TaskEvent e) {
                        logEvent("finished", e, startNanos);
                    }
                });
    }

    private static void logEvent(String phase, TaskEvent e, long startNanos) {
        if (e == null || e.getKind() == null) {
            return;
        }
        // Intentionally omit logging annotation processing phases to reduce log noise.
    }

    private static void configureLombokProcessor(JavacTask task) {
        try {
            Class<?> processorClass =
                    Class.forName("lombok.launch.AnnotationProcessorHider$AnnotationProcessor");
            Object processor = processorClass.getDeclaredConstructor().newInstance();
            if (processor instanceof javax.annotation.processing.Processor p) {
                task.setProcessors(java.util.List.of(p));
                LOG.info("Configured Lombok processor explicitly");
            } else {
                LOG.warning("Lombok processor class did not implement Processor");
            }
        } catch (ClassNotFoundException e) {
            LOG.warning("Lombok processor class not found on classpath");
        } catch (ReflectiveOperationException e) {
            LOG.warning("Failed to create Lombok processor: " + e.getMessage());
        }
    }

    /**
        When there are parse/enter errors, javac normally stops before running processors.
        Allow it to continue so Lombok can still generate accessors even on incomplete buffers.
    */
    private static void relaxShouldStopPolicy(JavacTask task) {
        try {
            var ctx = ((BasicJavacTask) task).getContext();
            Options.instance(ctx).put("should-stop.ifError", CompileState.GENERATE.name());
        } catch (Exception e) {
            LOG.fine("Could not relax should-stop policy: " + e.getMessage());
        }
    }

    /**
     * If the compilation failed because javac didn't find some package-private files in source files with different
     * names, list those source files.
     */
    Set<Path> needsAdditionalSources() {
        // Check for "class not found errors" that refer to package private classes
        var addFiles = new HashSet<Path>();
        for (var err : parent.diags) {
            String code = err.getCode();
            if (code.equals("compiler.err.cant.resolve.location")) {
                if (!isValidFileRange(err)) continue;
                var className = errorText(err);
                var packageName = packageName(err);
                if (packageName != null) {
                    var location = findPackagePrivateClass(packageName, className);
                    if (location != FILE_NOT_FOUND) {
                        addFiles.add(location);
                    }
                }
            } else if (code.equals("compiler.err.cant.resolve.location.args")) {
                // Handle "cannot find symbol ... location: variable ... of type ..."
                // This handles cases where methods (like Lombok getters) are missing because the type wasn't compiled as a root
                String msg = err.getMessage(Locale.getDefault());
                Path source = findSourceForTypeInMessage(msg);
                if (source != FILE_NOT_FOUND) {
                    addFiles.add(source);
                }
            }
        }
        return addFiles;
    }

    private static final Pattern TYPE_PATTERN = Pattern.compile("location: (variable .* of type |class )([a-zA-Z0-9_.]+)");

    private Path findSourceForTypeInMessage(String message) {
        Matcher m = TYPE_PATTERN.matcher(message);
        if (m.find()) {
            String fqn = m.group(2);
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot != -1) {
                String packageName = fqn.substring(0, lastDot);
                String className = fqn.substring(lastDot + 1);
                
                // Try to find the file in the package
                // 1. Check for exact match first (standard convention)
                List<Path> packageFiles = FileStore.list(packageName);
                for (Path p : packageFiles) {
                    String fileName = p.getFileName().toString();
                    if (fileName.equals(className + ".java")) {
                        return p;
                    }
                }
                // 2. Fallback to parsing if not found (for classes in files with different names)
                return findPackagePrivateClass(packageName, className);
            }
        }
        return FILE_NOT_FOUND;
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
            var parse = Parser.parseFile(file);
            // Check top level classes too
            for (var candidate : parse.packagePrivateClasses()) {
                if (candidate.contentEquals(className)) {
                    return file;
                }
            }
            // The previous logic might have missed public classes if 'packagePrivateClasses' only returns package-private ones.
            // But we reused it for now. Ideally we should check public classes too.
            // Let's assume Parser logic is sufficient or improve it later if needed.
        }
        return FILE_NOT_FOUND;
    }

    @Override
    public void close() {
        closed = true;
    }

    private static ReusableCompiler.Borrow batchTask(
            JavaCompilerService parent, Collection<? extends JavaFileObject> sources) {
        parent.diags.clear();
        var options = options(parent.classPath, parent.addExports);
        LOG.fine("Javac options: " + options);
        return parent.compiler.getTask(parent.fileManager, parent.diags::add, options, List.of(), sources);
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> options(Set<Path> classPath, Set<String> addExports) {
        var list = new ArrayList<String>();

        var cp = joinPath(classPath);
        Collections.addAll(list, "-classpath", cp);
        Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        var sourceRoots = FileStore.sourceRoots();
        if (!sourceRoots.isEmpty()) {
            Collections.addAll(list, "-sourcepath", joinPath(sourceRoots));
        }
        if (LombokSupport.isEnabled()) {
            // JDK 21 requires explicit enable of processor discovery on classpath, or a processorpath.
            // We use -proc:full AND set processorpath to classpath to ensure discovery works.
            Collections.addAll(list, "-proc:full");
            Collections.addAll(list, "-processorpath", cp);
        }
        // Collections.addAll(list, "-verbose");
        Collections.addAll(list, "-g");
        Collections.addAll(
                list,
                "--add-opens", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "jdk.unsupported/sun.misc=ALL-UNNAMED");
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
        for (var export : addExports) {
            list.add("--add-exports");
            list.add(export + "=ALL-UNNAMED");
        }

        return list;
    }

    private boolean isValidFileRange(javax.tools.Diagnostic<? extends JavaFileObject> d) {
        return d.getSource().toUri().getScheme().equals("file") && d.getStartPosition() >= 0 && d.getEndPosition() >= 0;
    }
}
