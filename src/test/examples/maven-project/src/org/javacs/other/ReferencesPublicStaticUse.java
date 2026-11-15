package org.javacs.other;

import org.javacs.example.ReferencesPublicStaticBase;

public class ReferencesPublicStaticUse {
    int read() {
        return ReferencesPublicStaticBase.COUNT;
    }

    void write() {
        ReferencesPublicStaticBase.COUNT = 3;
    }
}
