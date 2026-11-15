package org.javacs.example;

class RefThisSuperKeyword extends RefThisSuperBase {
    private int f;

    int read() {
        return this.f;
    }

    int readBase() {
        return super.g;
    }
}
