package org.javacs.example;

import lombok.Builder;

@Builder
class LombokBuilderCompletion {
    private String name;
    private int count;

    void test() {
        LombokBuilderCompletion.builder().
    }
}
