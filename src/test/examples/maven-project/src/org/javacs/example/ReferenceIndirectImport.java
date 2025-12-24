package org.javacs.example;

import org.javacs.other.ImportDirectly;

class ReferenceIndirectImport {
    void test() {
        var direct = new ImportDirectly();
        var indirect = direct.getImportIndirectly();
        indirect.memberOfIndirectImport();
    }
}