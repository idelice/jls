package org.javacs.example;

public class ReferencesGenericMethod {
    <T> T id(T t) {
        return t;
    }

    void calls() {
        id("a");
        this.<String>id("a");
    }
}
