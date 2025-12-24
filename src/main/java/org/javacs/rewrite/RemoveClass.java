package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.FindTypeDeclarationAt;
import org.javacs.lsp.TextEdit;

public class RemoveClass implements Rewrite {
    final Path file;
    final int position;

    public RemoveClass(Path file, int position) {
        this.file = file;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var task = compiler.parse(file);
        var type = new FindTypeDeclarationAt(task.task).scan(task.root, (long) position);
        TextEdit[] edits = {new EditHelper(task.task).removeTree(task.root, type)};
        return Map.of(file, edits);
    }
}
