package org.javacs.example;

class RefHideSuper {
    static void m() {}
}

class RefHideSub extends RefHideSuper {
    static void m() {}
}

class RefHideUse {
    void run() {
        RefHideSuper.m();
        RefHideSub.m();
    }
}
