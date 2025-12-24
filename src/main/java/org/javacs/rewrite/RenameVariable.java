package org.javacs.rewrite;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

public class RenameVariable implements Rewrite {
    final Path file;
    final int position;
    final String newName;

    public RenameVariable(Path file, int position, String newName) {
        this.file = file;
        this.position = position;
        this.newName = newName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var compile = compiler.compile(file)) {
            var trees = Trees.instance(compile.task);
            com.sun.source.tree.CompilationUnitTree root;
            try {
                root = compile.root(file);
            } catch (RuntimeException e) {
                return CANCELLED;
            }
            var found = new FindVariableAt(compile.task).scan(root, position);
            if (found == null) {
                return CANCELLED;
            }
            var rename = trees.getPath(root, found);
            var edits = new RenameHelper(compile).renameVariable(root, rename, newName);
            return Map.of(file, edits);
        }
    }
}
