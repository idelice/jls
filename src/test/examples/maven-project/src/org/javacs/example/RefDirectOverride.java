package org.javacs.example;

class RefOverrideBase {
    void m() {}
}

class RefOverrideSub extends RefOverrideBase {
    @Override
    void m() {}
}

class RefOverrideUse {
    void run() {
        RefOverrideSub b = new RefOverrideSub();
        b.m();
    }
}
