package org.javacs.example;

interface RefIfaceContract {
    void m();
}

class RefIfaceContractUse {
    void run(RefIfaceContract i) {
        i.m();
    }
}
