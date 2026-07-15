package org.javacs;

import com.sun.source.util.JavacTask;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

class ReusableCompiler {
    private final JavaCompiler compiler =
            ToolProvider.getSystemJavaCompiler();

    @SuppressWarnings("unchecked")
    <T> T compile(
            JavaFileManager fileManager,
            DiagnosticListener<? super JavaFileObject> diagnosticListener,
            List<String> options,
            Collection<? extends JavaFileObject> compilationUnits,
            Function<JavacTask, T> worker) {

        var task = (JavacTask) compiler.getTask(
                null, fileManager, diagnosticListener, options,
                null, compilationUnits);
        return worker.apply(task);
    }
}
