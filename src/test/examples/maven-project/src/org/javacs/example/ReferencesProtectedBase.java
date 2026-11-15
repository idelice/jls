package org.javacs.example;

public class ReferencesProtectedBase {
    protected int value;
}

class ReferencesProtectedUse {
    int read(ReferencesProtectedBase base) {
        return base.value;
    }
}
