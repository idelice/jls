package org.javacs.example;

public class ReferencesProtectedStaticBase {
    protected static int shared;
}

class ReferencesProtectedStaticUse {
    void read() {
        ReferencesProtectedStaticBase.shared = 1;
    }
}
