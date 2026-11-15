package org.javacs.example;

import static org.javacs.other.RefImportTarget.*;

public class RefStaticImportOnDemand {
    void test() {
        demandImportedMethod();
    }

    int field() {
        return DEMAND_IMPORTED_FIELD;
    }
}
