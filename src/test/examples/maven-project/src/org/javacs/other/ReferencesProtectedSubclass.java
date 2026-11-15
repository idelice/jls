package org.javacs.other;

import org.javacs.example.ReferencesProtectedBase;

public class ReferencesProtectedSubclass extends ReferencesProtectedBase {
    int readInherited() {
        return value;
    }

    int readThis() {
        return this.value;
    }
}
