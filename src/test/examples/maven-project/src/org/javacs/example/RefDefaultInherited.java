package org.javacs.example;

interface RefDefaultInheritedContract {
    default void m() {}
}

class RefDefaultInheritedImpl implements RefDefaultInheritedContract {}

class RefDefaultInheritedUse {
    void run(RefDefaultInheritedImpl c) {
        c.m();
    }
}
