package org.javacs.example;

public class ReferencesPrivateStaticField {
    private static int f;

    void unqualified() {
        f = 1;
    }

    void qualified() {
        ReferencesPrivateStaticField.f = 2;
    }

    void viaInstance(ReferencesPrivateStaticField obj) {
        obj.f = 3;
    }
}

class ReferencesPrivateStaticFieldOther {
    private static int f;

    void use() {
        f = 4;
    }
}
