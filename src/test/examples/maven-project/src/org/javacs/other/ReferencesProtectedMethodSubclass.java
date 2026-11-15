package org.javacs.other;

import org.javacs.example.ReferencesProtectedMethodBase;

public class ReferencesProtectedMethodSubclass extends ReferencesProtectedMethodBase {
    void inherited() {
        m();
    }

    void thisQualified() {
        this.m();
    }

    void subclassTyped(ReferencesProtectedMethodSubclass other) {
        other.m();
    }
}
