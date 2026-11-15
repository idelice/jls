package org.javacs.example;

abstract class RefAbstractBase {
    abstract void m();
}

class RefAbstractUse {
    void run(RefAbstractBase a) {
        a.m();
    }
}
