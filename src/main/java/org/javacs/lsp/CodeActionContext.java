package org.javacs.lsp;

import java.util.ArrayList;
import java.util.List;

public class CodeActionContext {
    public List<Diagnostic> diagnostics = new ArrayList<>();
    public List<String> only;
}
