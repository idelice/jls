package org.javacs.example;

interface RefIfaceStaticContract {
    static void s() {}
}

class RefIfaceStaticUse {
    void run() {
        RefIfaceStaticContract.s();
    }
}
