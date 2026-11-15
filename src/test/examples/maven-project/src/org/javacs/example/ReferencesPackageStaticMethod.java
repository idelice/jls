package org.javacs.example;

class ReferencesPackageStaticMethod {
    static void m() {}

    void unqualified() {
        m();
    }
}
