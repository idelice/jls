package org.javacs.lsp;

public class InlayHint {
    public Position position;
    public String label;
    /** 1 = Type, 2 = Parameter */
    public int kind;
    public boolean paddingRight;

    public InlayHint() {}

    public InlayHint(Position position, String label, int kind, boolean paddingRight) {
        this.position = position;
        this.label = label;
        this.kind = kind;
        this.paddingRight = paddingRight;
    }
}
