package org.javacs.example;

public class ReferencesPrivateStaticMethod {
    private static void m() {}

    void unqualified() {
        m();
    }

    void qualified() {
        ReferencesPrivateStaticMethod.m();
    }

    void viaInstance(ReferencesPrivateStaticMethod obj) {
        obj.m();
    }

    void methodReference() {
        Runnable r = ReferencesPrivateStaticMethod::m;
        r.run();
    }
}
