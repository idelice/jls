package org.javacs.example;

public class ReferencesFieldMultiDeclarator {
    private int a = 1, b = a + 1;

    int read() {
        return a + b;
    }
}
