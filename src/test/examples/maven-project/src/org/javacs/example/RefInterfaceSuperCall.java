package org.javacs.example;

interface RefIfaceSuperContract {
    default void m() {}
}

class RefIfaceSuperImpl implements RefIfaceSuperContract {
    public void m() {
        RefIfaceSuperContract.super.m();
    }
}
