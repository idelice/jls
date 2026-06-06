package org.javacs.example;

import java.util.List;
import java.util.stream.Collectors;

public class CompleteEdgeCases {
    private List<CompleteItemModel> items = List.of();

    void methodReference() {
        items.stream().map(CompleteItemModel::g)
    }

    void chainedWorkspaceMembers() {
        items.get(0).getParent().
    }

    void lambdaInference() {
        items.stream().map(item -> item.).collect(Collectors.toList());
    }

    void varEnhancedFor() {
        for (var item : items) {
            item.
        }
    }

    void unfinishedLine() {
        items.get(0).
        System.out.println("next");
    }
}
