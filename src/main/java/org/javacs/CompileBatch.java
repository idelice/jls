package org.javacs;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.util.*;
import javax.tools.*;

/** One attributed source analysis. The borrowed context lives until the result closes. */
public final class CompileBatch implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("main");
    private final ReusableCompiler.Borrow borrow;
    final JavacTask task;
    final Trees trees;
    final Elements elements;
    final Types types;
    final List<CompilationUnitTree> roots = new ArrayList<>();
    final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();

    CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files, boolean oneShot) {
        if (files.isEmpty()) throw new IllegalArgumentException("No source files to analyze");
        parent.prepareFileManager();
        borrow = parent.compiler.borrow(parent.fileManager, diagnostics::add,
                options(parent.classPath, parent.addExports, parent.extraArgs), files, oneShot);
        task = borrow.task;
        trees = Trees.instance(task);
        elements = task.getElements();
        types = task.getTypes();
        var started = System.nanoTime();
        var parsed = new HashSet<java.net.URI>();
        var injector = new LombokStubInjector(borrow.task.getContext());
        task.addTaskListener(new TaskListener() {
            @Override public void finished(TaskEvent event) {
                var unit = event.getCompilationUnit();
                if (event.getKind() == TaskEvent.Kind.PARSE && unit != null
                        && parsed.add(unit.getSourceFile().toUri())) injector.inject(unit);
            }
        });
        try {
            task.parse().forEach(roots::add);
            analyze();
        } catch (IOException | RuntimeException | Error failure) {
            borrow.invalidate();
            borrow.close();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new RuntimeException(failure);
        }
        LOG.info("[analysis] context=" + borrow.id() + " reused=" + borrow.reused
                + " proc=none requested=" + files.size() + " parsed=" + parsed.size()
                + " diagnostics=" + diagnostics.size() + " ms=" + (System.nanoTime() - started) / 1_000_000);
    }

    private void analyze() throws IOException {
        try {
            task.analyze();
        } catch (RuntimeException | AssertionError failure) {
            // Retain partial trees for navigation, but never reuse an internally failed context.
            borrow.invalidate();
            LOG.warning("[analysis] partial context=" + borrow.id() + " cause=" + failure);
        }
    }

    @Override public void close() { borrow.close(); }

    static List<String> options(Set<Path> classPath, Set<String> addExports, List<String> extraArgs) {
        var list = new ArrayList<String>();

        Collections.addAll(list, "-classpath", joinPath(classPath));
        if (!targetsJava8OrEarlier(extraArgs)) {
            Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        }

        Collections.addAll(list, "-XDshould-stop.ifError=FLOW");

        Collections.addAll(list, "-Xmaxerrs", "9999");
        Collections.addAll(list, "-Xmaxwarns", "9999");
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
        list.addAll(analysisArguments(extraArgs));
        // javac rejects --add-exports for system modules together with --release.
        var release = extraArgs.stream().anyMatch(arg -> arg.equals("--release") || arg.startsWith("--release="));
        if (!release) {
            for (var export : new TreeSet<>(addExports)) {
                list.add("--add-exports");
                list.add(export + "=ALL-UNNAMED");
            }
        }

        Collections.addAll(list, "-proc:none", "-implicit:none", "-Xprefer:source");
        return list;
    }

    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream()
                .map(Path::toString)
                .sorted()
                .collect(Collectors.joining(File.pathSeparator));
    }

    private static boolean targetsJava8OrEarlier(List<String> extraArgs) {
        for (int i = 0; i < extraArgs.size() - 1; i++) {
            var arg = extraArgs.get(i);
            if (!"--release".equals(arg) && !"-source".equals(arg) && !"-target".equals(arg)) continue;
            var level = parseJavaLevel(extraArgs.get(i + 1));
            if (level > 0) return level <= 8;
        }
        return false;
    }

    private static int parseJavaLevel(String value) {
        if (value == null || value.isBlank()) return -1;
        if (value.startsWith("1.")) value = value.substring(2);
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return -1; }
    }

    /** Discard build-only options so settings cannot enable processors or class emission. */
    private static List<String> analysisArguments(List<String> arguments) {
        var result = new ArrayList<String>();
        var operands = Set.of("-processor", "-processorpath", "--processor-path", "--processor-module-path", "-d", "-s", "-h");
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            var name = argument.contains("=") ? argument.substring(0, argument.indexOf('=')) : argument;
            if (operands.contains(name)) {
                if (argument.equals(name) && i + 1 < arguments.size()) i++;
            } else if (!argument.startsWith("-proc:") && !argument.startsWith("-A")
                    && !argument.startsWith("-implicit:") && !argument.startsWith("-Xprefer:")) {
                result.add(argument);
            }
        }
        return result;
    }
}
