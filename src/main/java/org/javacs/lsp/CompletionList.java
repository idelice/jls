package org.javacs.lsp;

import java.util.ArrayList;
import java.util.List;

public class CompletionList {
    public boolean isIncomplete;
    public List<CompletionItem> items;

    public CompletionList() {
        this.items = new ArrayList<>();
    }

    public CompletionList(boolean isIncomplete, List<CompletionItem> items) {
        this.isIncomplete = isIncomplete;
        this.items = items;
    }
}
