package org.javacs.example;

import java.util.List;

public class CompleteInMiddleOfLambdaChain {
    boolean isVip() {
        return false;
    }

    int instanceMethod() {
        return 1;
    }

    private static List<CompleteInMiddleOfLambdaChain> getItems() {
        return List.of();
    }

    void test() {
        getItems().stream()
                .filter(item -> item..isVip())
                .toList();
    }
}
