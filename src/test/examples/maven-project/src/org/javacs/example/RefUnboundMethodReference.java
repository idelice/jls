package org.javacs.example;

interface RefUnboundApply {
    void apply(RefUnboundTarget target);
}

class RefUnboundTarget {
    void m() {}
}

class RefUnboundUse {
    RefUnboundApply make() {
        return RefUnboundTarget::m;
    }
}
