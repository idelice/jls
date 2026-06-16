package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import org.javacs.lsp.*;

class Parser {
    private static final JavaCompiler COMPILER = ServiceLoader.load(JavaCompiler.class).iterator().next();
    private static final SourceFileManager FILE_MANAGER = new SourceFileManager();

    /** Create a task that compiles a single file */
    private static JavacTask singleFileTask(JavaFileObject file) {
        return (JavacTask)
                COMPILER.getTask(null, FILE_MANAGER, Parser::ignoreError, List.of(), List.of(), List.of(file));
    }

    final JavaFileObject file;
    final String contents;
    final JavacTask task;
    final CompilationUnitTree root;
    final Trees trees;

    private Parser(JavaFileObject file) {
        this.file = file;
        try {
            this.contents = file.getCharContent(false).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.task = singleFileTask(file);
        try {
            this.root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.trees = Trees.instance(task);
    }

    static void parseFile(Path file) {
        parseJavaFileObject(new SourceFileObject(file));
    }

    static Parser parseJavaFileObject(JavaFileObject file) {
        // Parse directly from the current SourceFileObject document contents.
        // This avoids cross-request stale AST races on shared global parse state.
        return new Parser(file);
    }


    private static void ignoreError(javax.tools.Diagnostic<? extends JavaFileObject> __) {
        // Too noisy, this only comes up in parse tasks which tend to be less important
        // LOG.warning(err.getMessage(Locale.getDefault()));
    }
}
