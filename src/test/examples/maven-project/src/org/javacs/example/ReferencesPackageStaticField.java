package org.javacs.example;

class ReferencesPackageStaticField {
    static int f;

    void unqualified() {
        f = 1;
    }
}

class ReferencesPackageStaticFieldUse {
    void qualified() {
        ReferencesPackageStaticField.f = 2;
    }
}
