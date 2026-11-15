package org.javacs.example;

class TypeRefAccessorUser {
    int read(TypeRefAccessorRecord p) {
        return p.x();
    }

    TypeRefAccessorApply reference() {
        return TypeRefAccessorRecord::x;
    }
}
