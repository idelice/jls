package org.javacs.example;

import static org.javacs.other.RefShadowImportTarget.shadowedMethod;

public class RefStaticImportShadowedByLocalMethod {
    void shadowedMethod() {}

    void test() {
        shadowedMethod();
    }
}
