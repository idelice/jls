package org.javacs.example;

class RefInheritedBase {
    void m() {}
}

class RefInheritedSub extends RefInheritedBase {}

class RefInheritedUse {
    void run() {
        RefInheritedSub b = new RefInheritedSub();
        b.m();
    }
}
