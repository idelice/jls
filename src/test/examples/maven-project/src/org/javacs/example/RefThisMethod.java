package org.javacs.example;

class RefThisMethod {
    void m() {}

    void run() {
        m();
        this.m();
    }
}
