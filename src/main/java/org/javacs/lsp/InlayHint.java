package org.javacs.lsp;

public class InlayHint {
    public Position position;
    public String label;
    public Integer kind;
    public String tooltip;
    public Boolean paddingLeft;
    public Boolean paddingRight;

    public InlayHint() {}

    public InlayHint(Position position, String label, Integer kind) {
        this.position = position;
        this.label = label;
        this.kind = kind;
    }
}
