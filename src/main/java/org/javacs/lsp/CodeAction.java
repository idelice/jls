package org.javacs.lsp;

import com.google.gson.JsonElement;
import java.util.List;

public class CodeAction {
    public String title, kind;
    public List<Diagnostic> diagnostics;
    public WorkspaceEdit edit;
    public Command command;
    /** Opaque token used by codeAction/resolve to reconstruct the Rewrite lazily. */
    public JsonElement data;
    public static CodeAction NONE;
}
