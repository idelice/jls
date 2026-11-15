package org.javacs.example;

class RefUnrelatedLocalSameName {
    int first() {
        int x = 1;
        return x + x;
    }

    int second() {
        int x = 2;
        return x * x;
    }
}
