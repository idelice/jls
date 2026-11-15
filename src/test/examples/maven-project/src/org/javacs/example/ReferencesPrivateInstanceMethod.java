package org.javacs.example;

public class ReferencesPrivateInstanceMethod {
    private void m() {}

    void direct() {
        m();
    }

    void qualified() {
        this.m();
    }

    void methodReference() {
        Runnable r = this::m;
        r.run();
    }
}
