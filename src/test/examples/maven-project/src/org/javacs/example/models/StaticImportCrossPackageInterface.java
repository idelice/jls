package org.javacs.example.models;

public interface StaticImportCrossPackageInterface {
    String HELLO = "hello";

    static String doit() {
        return "hello";
    }
}
