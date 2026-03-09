package org.javacs.example.service;

import static org.javacs.example.models.StaticImportCrossPackageInterface.*;

public class StaticImportCrossPackageUsage {
    String test() {
        return HELLO;
    }

    String testMethod() {
        return doit();
    }
}
