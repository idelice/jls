package org.javacs.example;

class RefInheritedFieldSub extends RefInheritedFieldSuper {
    int f;

    void use(RefInheritedFieldSub s) {
        f = 1;
        this.f = 2;
        super.f = 3;
        ((RefInheritedFieldSuper) s).f = 4;
    }
}
