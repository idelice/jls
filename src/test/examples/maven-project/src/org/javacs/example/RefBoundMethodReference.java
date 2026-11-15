package org.javacs.example;

interface RefBoundApply {
    void apply();
}

class RefBoundTarget {
    void m() {}
}

class RefBoundUse {
    RefBoundApply make(RefBoundTarget c) {
        return c::m;
    }
}
