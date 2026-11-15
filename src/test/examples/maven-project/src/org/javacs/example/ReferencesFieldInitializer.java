package org.javacs.example;

public class ReferencesFieldInitializer {
    private int f = 1;
    private int copy = f;

    {
        f = 2;
    }

    ReferencesFieldInitializer() {
        f = 3;
    }

    int read() {
        return f;
    }
}
