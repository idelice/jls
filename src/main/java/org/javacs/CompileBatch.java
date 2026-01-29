package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.util.*;
import javax.tools.*;

class CompileBatch implements AutoCloseable {
    static final int MAX_COMPLETION_ITEMS = 50;
    private static final Logger LOG = Logger.getLogger("main");

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

    CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files) {
        this(parent, files, true);  // Default: allow AP
    }

    /**
     * Create a compilation batch with optional annotation processing.
     *
     * @param parent the compiler service
     * @param files the files to compile
     * @param allowAP if false, uses non-AP options even if Lombok is present
     */
    CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files, boolean allowAP) {
        this.parent = parent;
        this.borrow = batchTask(parent, files, allowAP);
        this.task = borrow.task;
        this.trees = Trees.instance(borrow.task);
        this.elements = borrow.task.getElements();
        this.types = borrow.task.getTypes();
        this.roots = new ArrayList<>();

        try {
            for (var t : borrow.task.parse()) {
                roots.add(t);
            }
            // The results of borrow.task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            borrow.task.analyze();
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
        }
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
            var parse = Parser.parseFile(file);
            for (var candidate : parse.packagePrivateClasses()) {
                if (candidate.contentEquals(className)) {
                    return file;
                }
            }
        }
        return FILE_NOT_FOUND;
    }

    @Override
    public void close() {
        closed = true;
    }

    private static ReusableCompiler.Borrow batchTask(
            JavaCompilerService parent,
            Collection<? extends JavaFileObject> sources,
            boolean allowAP) {
        parent.diags.clear();
        var options = allowAP
                ? options(parent.classPath, parent.addExports, parent.extraArgs, parent.releaseVersion)
                : optionsWithoutAP(parent.classPath, parent.addExports, parent.extraArgs, parent.releaseVersion);
        return parent.compiler.getTask(parent.fileManager, parent.diags::add, options, List.of(), sources);
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    /**
     * Check if Lombok is present on the classpath.
     * Looks for lombok.jar or the lombok-specific directory.
     */
    private static boolean isLombokPresent(Set<Path> classPath) {
        return classPath.stream()
                .anyMatch(
                        p -> {
                            var name = p.getFileName().toString().toLowerCase();
                            return name.startsWith("lombok") && (name.endsWith(".jar") || name.endsWith("-all.jar"));
                        });
    }

    private static List<String> options(Set<Path> classPath, Set<String> addExports, Set<String> extraArgs, String releaseVersion) {
        var list = new ArrayList<String>();

        Collections.addAll(list, "-classpath", joinPath(classPath));
        Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        // Collections.addAll(list, "-verbose");

        // Annotation processing is DISABLED by default due to javac AP infrastructure bugs.
        // javac 21.0.9 has critical bugs when processing Lombok annotations (AssertionErrors, NPEs).
        // Even with explicit processor specification, it fails frequently.
        // Users can enable AP at their own risk by setting an environment variable or config.
        // TODO: In a future JDK version with AP fixes, consider enabling this.
        Collections.addAll(list, "-proc:none");

        Collections.addAll(list, "-g");

        // Set release version to match project's target Java version
        // if (releaseVersion != null && !releaseVersion.isEmpty()) {
        //     Collections.addAll(list, "--release", releaseVersion);
        // }

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
        for (var export : addExports) {
            list.add("--add-exports");
            list.add(export + "=ALL-UNNAMED");
        }

        return list;
    }

    /**
     * Create compilation options with annotation processing disabled.
     * Used for retrying compilation after AP failure.
     */
    static List<String> optionsWithoutAP(Set<Path> classPath, Set<String> addExports, Set<String> extraArgs, String releaseVersion) {
        var list = new ArrayList<String>();
        Collections.addAll(list, "-classpath", joinPath(classPath));
        Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        Collections.addAll(list, "-proc:none");
        Collections.addAll(list, "-g");

        // Set release version to match project's target Java version
        // if (releaseVersion != null && !releaseVersion.isEmpty()) {
        //     Collections.addAll(list, "--release", releaseVersion);
        // }

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
