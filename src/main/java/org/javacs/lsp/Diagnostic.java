package org.javacs.lsp;

import java.util.List;

public class Diagnostic {
    public Range range;
    public Integer severity;
    public String code, source, message;
    // TODO need to upgrade to vscode-languageclient 5.2.2 when it comes out
    public List<Integer> tags; // DiagnosticTag
}
