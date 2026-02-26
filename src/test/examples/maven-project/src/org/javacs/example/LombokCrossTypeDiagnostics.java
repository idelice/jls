package org.javacs.example;

class LombokCrossTypeDiagnostics {
    void test() {
        var model = new LombokCrossTypeModel();
        model.setName("value");
        model.getName();
    }
}
