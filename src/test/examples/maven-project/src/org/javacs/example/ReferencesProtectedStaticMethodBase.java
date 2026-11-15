package org.javacs.example;

public class ReferencesProtectedStaticMethodBase {
    protected static void m() {}

    void samePackage() {
        ReferencesProtectedStaticMethodBase.m();
    }
}
