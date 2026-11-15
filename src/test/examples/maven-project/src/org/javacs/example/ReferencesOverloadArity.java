package org.javacs.example;

public class ReferencesOverloadArity {
    void m(int a) {}

    void m(int a, int b) {}

    void one() {
        m(1);
    }

    void two() {
        m(1, 2);
    }
}
