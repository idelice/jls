package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.main.JavaCompiler;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;
import javax.lang.model.util.*;
import javax.tools.*;

public class CompileBatch implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("main");

    final JavaCompilerService parent;
    boolean closed;

    final JavacTask task;
    final Trees trees;
    final Elements elements;
    final Types types;
    final List<CompilationUnitTree> roots;
    CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files) {
        this.parent = parent;
        LOG.info("[compile] CompileBatch — starting compile of " + files.size() + " file(s)");
        parent.diags.clear();
        var options = options(parent.classPath, parent.addExports, parent.extraArgs, parent.lombokPresentOnClasspath, parent.isBuildOutputAvailable(), parent.targetNeedsLombok);

        // single mutable holder to extract result from worker lambda
        var holder = new Object() {
            JavacTask task;
            Trees trees;
            Elements elements;
            Types types;
            List<CompilationUnitTree> roots;
        };

        parent.compiler.compile(
                parent.fileManager,
                parent.diags::add,
                options,
                files,
                task -> {
                    holder.task = task;
                    holder.trees = Trees.instance(task);
                    holder.elements = task.getElements();
                    holder.types = task.getTypes();
                    holder.roots = new ArrayList<>();
                    if (parent.targetNeedsLombok) {
                    }
                    try {
                        for (var t : task.parse()) {
                            holder.roots.add(t);
                        }
                        try {
                            var impl = (JavacTaskImpl) task;
                            impl.enter();
                            var compiler = JavaCompiler.instance(impl.getContext());
                            compiler.attribute(compiler.todo);
                        } catch (Throwable e) {
                            LOG.warning("[compiler] analyze failed: "
                                    + e.getClass().getName() + ": " + e.getMessage());
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });

        this.task = holder.task;
        this.trees = holder.trees;
        this.elements = holder.elements;
        this.types = holder.types;
        this.roots = holder.roots;
    }

    Set<Path> needsAdditionalSources() {
        var addFiles = new HashSet<Path>();
        for (var err : parent.diags) {
            var code = err.getCode();
            if (code.equals("compiler.err.cant.resolve.location")) {
                if (!isValidFileRange(err)) continue;
                var className = errorText(err);
                var pkg = packageName(err);
                if (pkg != null) {
                    var location = findPackagePrivateClass(pkg, className);
                    if (location != FILE_NOT_FOUND) {
                        addFiles.add(location);
                    }
                }
            }
            // Handle general missing symbols (stale .class files from edited dependencies)
            if (code.equals("compiler.err.cant.resolve")) {
                var symbolName = extractSymbolName(err);
                if (symbolName != null && !symbolName.isEmpty()) {
                    var location = findSourceFile(symbolName);
                    if (location != FILE_NOT_FOUND) {
                        LOG.info("[compile] stale-classfile: adding source for " + symbolName + " at " + location);
                        addFiles.add(location);
                    }
                }
            }
        }
        return addFiles;
    }

    private String errorText(Diagnostic<? extends JavaFileObject> err) {
        var file = Paths.get(err.getSource().toUri());
        var contents = FileStore.contents(file);
        var begin = (int) err.getStartPosition();
        var end = (int) err.getEndPosition();
        return contents.substring(begin, end);
    }

    private String packageName(Diagnostic<? extends JavaFileObject> err) {
        var file = Paths.get(err.getSource().toUri());
        return FileStore.packageName(file);
    }

    private static final Path FILE_NOT_FOUND = Paths.get("");

    private Path findPackagePrivateClass(String packageName, String className) {
        for (var file : FileStore.list(packageName)) {
            var parse = Parser.parseJavaFileObject(new SourceFileObject(file));
            for (var declaration : parse.root.getTypeDecls()) {
                if (!(declaration instanceof ClassTree cls)) continue;
                if (cls.getModifiers().getFlags().contains(Modifier.PUBLIC)) continue;
                if (cls.getSimpleName().contentEquals(className)) {
                    return file;
                }
            }
        }
        return FILE_NOT_FOUND;
    }

    /** Extract the missing symbol name from a "cannot resolve symbol" diagnostic.
     *  The error message format is: "cannot find symbol\n  symbol:   method foo()\n  location: class Bar"
     *  We extract the symbol name (without trailing () for methods). */
    private String extractSymbolName(Diagnostic<? extends JavaFileObject> err) {
        var msg = err.getMessage(java.util.Locale.US);
        if (msg == null) return null;
        var symbolPattern = java.util.regex.Pattern.compile("symbol:\\s+\\w+\\s+(\\S+)");
        var matcher = symbolPattern.matcher(msg);
        if (matcher.find()) {
            var name = matcher.group(1);
            if (name.endsWith("()")) name = name.substring(0, name.length() - 2);
            return name;
        }
        return null;
    }

    /** Find a workspace source file that defines a type with the given simple name.
     *  Searches all packages. Returns FILE_NOT_FOUND if nothing matches. */
    private Path findSourceFile(String simpleName) {
        for (var file : FileStore.all()) {
            var fileName = file.getFileName().toString();
            if (!fileName.endsWith(".java")) continue;
            var fileBaseName = fileName.substring(0, fileName.length() - ".java".length());
            if (!fileBaseName.equals(simpleName)) continue;
            try {
                var source = new SourceFileObject(file);
                var parser = Parser.parseJavaFileObject(source);
                for (var decl : parser.root.getTypeDecls()) {
                    if (decl instanceof com.sun.source.tree.ClassTree cls) {
                        if (cls.getSimpleName().toString().equals(simpleName)) {
                            return file;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return FILE_NOT_FOUND;
    }

    @Override
    public void close() {
        closed = true;
    }

    static List<String> options(Set<Path> classPath, Set<String> addExports, List<String> extraArgs, boolean lombokPresent, boolean useBuildOutput, boolean targetNeedsLombok) {
        var list = new ArrayList<String>();

        Collections.addAll(list, "-classpath", joinPath(classPath));
        if (!targetsJava8OrEarlier(extraArgs)) {
            Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
        }

        // Lombok AP intentionally disabled — types resolve from .class build output on classpath
        Collections.addAll(list, "-proc:none");

        Collections.addAll(list, "-g");
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
        list.addAll(extraArgs);
        var sortedExports = new ArrayList<>(addExports);
        Collections.sort(sortedExports);
        for (var export : sortedExports) {
            list.add("--add-exports");
            list.add(export + "=ALL-UNNAMED");
        }

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

    private boolean isValidFileRange(Diagnostic<? extends JavaFileObject> d) {
        return d.getSource().toUri().getScheme().equals("file")
                && d.getStartPosition() >= 0
                && d.getEndPosition() >= 0;
    }
}
