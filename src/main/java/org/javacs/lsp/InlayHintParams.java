package org.javacs.lsp;

public class InlayHintParams {
    public TextDocumentIdentifier textDocument;
    public Range range;

    public InlayHintParams() {}

    public InlayHintParams(TextDocumentIdentifier textDocument, Range range) {
        this.textDocument = textDocument;
        this.range = range;
    }
}
