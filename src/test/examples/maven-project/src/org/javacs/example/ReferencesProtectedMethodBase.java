package org.javacs.example;

public class ReferencesProtectedMethodBase {
    protected void m() {}

    void samePackage() {
        m();
    }
}
