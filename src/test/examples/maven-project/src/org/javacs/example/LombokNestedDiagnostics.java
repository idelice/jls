package org.javacs.example;

class LombokNestedDiagnostics {
    void test() {
        var outer = new LombokNestedOuter();
        outer.getBar().getName();
    }
}
