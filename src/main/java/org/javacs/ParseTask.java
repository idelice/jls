package org.javacs;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;

public record ParseTask(JavacTask task, CompilationUnitTree root) {
}
