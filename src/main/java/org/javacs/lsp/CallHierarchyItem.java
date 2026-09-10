package org.javacs.lsp;

import java.net.URI;

public class CallHierarchyItem {
    public String name, detail;
    public int kind;
    public URI uri;
    public Range range, selectionRange;
    public Object data;

    public CallHierarchyItem() {}
}
