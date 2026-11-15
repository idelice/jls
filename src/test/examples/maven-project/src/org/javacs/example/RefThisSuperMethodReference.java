package org.javacs.example;

interface RefThisSuperApply {
    void apply();
}

class RefThisSuperBase {
    void m() {}
}

class RefThisSuperSub extends RefThisSuperBase {
    void m() {}

    RefThisSuperApply thisRef() {
        return this::m;
    }

    RefThisSuperApply superRef() {
        return super::m;
    }
}
