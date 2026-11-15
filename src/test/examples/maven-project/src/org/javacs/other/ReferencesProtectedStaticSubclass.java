package org.javacs.other;

import org.javacs.example.ReferencesProtectedStaticBase;

public class ReferencesProtectedStaticSubclass extends ReferencesProtectedStaticBase {
    int readInherited() {
        return shared;
    }
}
