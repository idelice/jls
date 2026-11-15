package org.javacs.example;

class RefChainedA {
    RefChainedB b() {
        return new RefChainedB();
    }
}

class RefChainedB {
    RefChainedC c() {
        return new RefChainedC();
    }
}

class RefChainedC {
    void done() {}
}

class RefChainedUse {
    void run(RefChainedA a) {
        a.b().c();
    }
}
