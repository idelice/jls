package org.javacs.example;

interface RefArrayMaker {
    RefArrayCtorTarget[] make(int size);
}

class RefArrayCtorTarget {}

class RefArrayCtorUse {
    RefArrayMaker f = RefArrayCtorTarget[]::new;
}
