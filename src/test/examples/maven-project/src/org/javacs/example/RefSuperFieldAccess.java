package org.javacs.example;

class RefSuperFieldBase {
    int f;
}

class RefSuperFieldSub extends RefSuperFieldBase {
    int f;

    void run() {
        f = 1;
        this.f = 2;
        super.f = 3;
    }
}
