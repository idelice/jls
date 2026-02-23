package org.javacs.example;

public class LombokPatternCompletion {
    static class SomePojo {}

    void test(Object obj) {
        if (obj instanceof SomePojo somepojo) {
            somepojo
        }
    }
}
