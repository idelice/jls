package org.javacs.lsp;

import java.util.List;

public class CallHierarchyIncomingCall {
    public CallHierarchyItem from;
    public List<Range> fromRanges;

    public CallHierarchyIncomingCall() {}
}
