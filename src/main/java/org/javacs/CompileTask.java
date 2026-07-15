package org.javacs;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public class CompileTask implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger("main");

    public final JavacTask task;
    public final Trees trees;
    public final Elements elements;
    public final Types types;
    public final List<CompilationUnitTree> roots;
    public final List<Diagnostic<? extends JavaFileObject>> diagnostics;
    private final Runnable close;

    public CompilationUnitTree root() {
        if (roots.isEmpty()) throw new RuntimeException("0");
        if (roots.size() != 1) {
            LOG.fine(String.format("[perf] compile_root_ambiguous roots=%d using_first", roots.size()));
        }
        return roots.getFirst();
    }

    public CompilationUnitTree root(Path file) {
        for (var root : roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) return root;
        }
        return null;
    }

    public CompilationUnitTree root(JavaFileObject file) {
        for (var root : roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) return root;
        }
        return null;
    }

    public CompileTask(
            JavacTask task,
            Trees trees,
            Elements elements,
            Types types,
            List<CompilationUnitTree> roots,
            List<Diagnostic<? extends JavaFileObject>> diagnostics,
            Runnable close) {
        this.task = task;
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.roots = roots;
        this.diagnostics = diagnostics;
        this.close = close;
    }

    @Override
    public void close() {
        close.run();
    }
}
