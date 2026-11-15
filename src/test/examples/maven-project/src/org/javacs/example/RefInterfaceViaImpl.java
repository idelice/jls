package org.javacs.example;

interface RefIfaceViaImplContract {
    void m();
}

abstract class RefIfaceViaImplImpl implements RefIfaceViaImplContract {}

class RefIfaceViaImplUse {
    void run(RefIfaceViaImplImpl x) {
        x.m();
    }
}
