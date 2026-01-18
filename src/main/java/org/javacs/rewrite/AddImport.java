package org.javacs.rewrite;

import com.sun.source.tree.*;
import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class AddImport implements Rewrite {
    final Path file;
    final String className;

    public AddImport(Path file, String className) {
        this.file = file;
        this.className = className;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var task = compiler.parse(file);
        var edits = createTextEdits(className, task.root, com.sun.source.util.Trees.instance(task.task).getSourcePositions());
        return Map.of(file, edits);
    }

    public static TextEdit[] createTextEdits(
            String className,
            CompilationUnitTree root,
            com.sun.source.util.SourcePositions sourcePositions) {
        var point = insertPosition(className, root, sourcePositions);
        var text = "import " + className + ";\n";
        return new TextEdit[] {new TextEdit(new Range(point, point), text)};
    }

    private static Position insertPosition(
            String className, CompilationUnitTree root, com.sun.source.util.SourcePositions sourcePositions) {
        var imports = root.getImports();
        for (var i : imports) {
            var next = i.getQualifiedIdentifier().toString();
            if (className.compareTo(next) < 0) {
                return insertBefore(root, sourcePositions, i);
            }
        }
        if (!imports.isEmpty()) {
            var last = imports.get(imports.size() - 1);
            return insertAfter(root, sourcePositions, last);
        }
        if (root.getPackage() != null) {
            return insertAfter(root, sourcePositions, root.getPackage());
        }
        return new Position(0, 0);
    }

    private static Position insertBefore(
            CompilationUnitTree root, com.sun.source.util.SourcePositions sourcePositions, Tree i) {
        var offset = sourcePositions.getStartPosition(root, i);
        var line = (int) root.getLineMap().getLineNumber(offset);
        return new Position(line - 1, 0);
    }

    private static Position insertAfter(
            CompilationUnitTree root, com.sun.source.util.SourcePositions sourcePositions, Tree i) {
        var offset = sourcePositions.getStartPosition(root, i);
        var line = (int) root.getLineMap().getLineNumber(offset);
        return new Position(line, 0);
    }
}
