package org.javacs.lsp;

import java.util.List;

public class CallHierarchyOutgoingCall {
    public CallHierarchyItem to;
    public List<Range> fromRanges;

    public CallHierarchyOutgoingCall() {}
}
