package org.javacs.example;

public class ReferencesOverloadTypes {
    void m(int a) {}

    void m(String a) {}

    void callInt() {
        m(1);
    }

    void callString() {
        m("x");
    }
}
