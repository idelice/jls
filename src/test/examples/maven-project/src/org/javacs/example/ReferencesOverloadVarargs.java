package org.javacs.example;

public class ReferencesOverloadVarargs {
    void m(int... a) {}

    void m(int a, int b) {}

    void callFixed() {
        m(1, 2);
    }

    void callVarargsNone() {
        m();
    }

    void callVarargsOne() {
        m(5);
    }
}
