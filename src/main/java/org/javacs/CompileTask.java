package org.javacs;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public class CompileTask implements AutoCloseable {
    public record SourceStamp(long modifiedMillis, int version) {
    }

    public final JavacTask task;
    public final List<CompilationUnitTree> roots;
    public final List<Diagnostic<? extends JavaFileObject>> diagnostics;
    public final Map<Path, SourceStamp> sourceStamps;
    private final Runnable close;

    public CompilationUnitTree root() {
        if (roots.isEmpty()) {
            throw new RuntimeException("0");
        }
        if (roots.size() != 1) {
            LOG.fine(String.format("[perf] compile_root_ambiguous roots=%d using_first", roots.size()));
        }
        return roots.getFirst();
    }

    public CompilationUnitTree root(Path file) {
        for (var root : roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) {
                return root;
            }
        }
        throw new RuntimeException("not found");
    }

    public CompilationUnitTree root(JavaFileObject file) {
        for (var root : roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) {
                return root;
            }
        }
        throw new RuntimeException("not found");
    }

    public CompileTask(
            JavacTask task,
            List<CompilationUnitTree> roots,
            List<Diagnostic<? extends JavaFileObject>> diagnostics,
            Map<Path, SourceStamp> sourceStamps,
            Runnable close) {
        this.task = task;
        this.roots = roots;
        this.diagnostics = diagnostics;
        this.sourceStamps = sourceStamps;
        this.close = close;
    }

    @Override
    public void close() {
        close.run();
    }

    private static final Logger LOG = Logger.getLogger("main");
}
