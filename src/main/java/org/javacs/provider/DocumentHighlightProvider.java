package org.javacs.provider;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.javacs.CompilerProvider;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.DocumentHighlight;
import org.javacs.lsp.DocumentHighlightKind;

public class DocumentHighlightProvider {
    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndexRouter;
    private final Path file;
    private final int line;
    private final int column;

    public DocumentHighlightProvider(
            CompilerProvider compiler, TypeIndexRouter typeIndexRouter, Path file, int line, int column) {
        this.compiler = compiler;
        this.typeIndexRouter = typeIndexRouter;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<DocumentHighlight> find() {
        var fileUri = file.toUri();
        var refs =
                new ReferenceProvider(compiler, typeIndexRouter, file, line, column, true).find();
        var highlights = new ArrayList<DocumentHighlight>();
        for (var loc : refs) {
            if (isSameFile(loc.uri, fileUri)) {
                var h = new DocumentHighlight();
                h.range = loc.range;
                h.kind = DocumentHighlightKind.Text;
                highlights.add(h);
            }
        }
        return highlights;
    }

    private static boolean isSameFile(URI a, URI b) {
        return a.normalize().equals(b.normalize());
    }
}
