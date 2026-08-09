package org.javacs.lsp;

import java.util.List;

/** LSP 3.17 pull-model document diagnostic response ({@code textDocument/diagnostic}). */
public class DocumentDiagnosticReport {
    public final String kind;
    public final String resultId;
    public final List<Diagnostic> items;

    public DocumentDiagnosticReport(List<Diagnostic> items) {
        this("full", null, items);
    }

    public DocumentDiagnosticReport(String kind, String resultId, List<Diagnostic> items) {
        this.kind = kind;
        this.resultId = resultId;
        this.items = items;
    }
}
