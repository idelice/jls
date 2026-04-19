package org.javacs.lsp;

import java.util.List;

/** LSP 3.17 pull-model document diagnostic response ({@code textDocument/diagnostic}). */
public class DocumentDiagnosticReport {
    public final String kind = "full";
    public final List<Diagnostic> items;

    public DocumentDiagnosticReport(List<Diagnostic> items) {
        this.items = items;
    }
}
