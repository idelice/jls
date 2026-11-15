package org.javacs.example;

class RefSuperBase {
    void m() {}
}

class RefSuperSub extends RefSuperBase {
    void m() {
        super.m();
    }
}
