package org.javacs.example;

class RefPrivateBase {
    private void m() {}
}

class RefPrivateSub extends RefPrivateBase {
    void m() {}
}

class RefPrivateUse {
    void run() {
        RefPrivateSub sub = new RefPrivateSub();
        sub.m();
    }
}
