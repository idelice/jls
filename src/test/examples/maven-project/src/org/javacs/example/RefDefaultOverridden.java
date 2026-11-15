package org.javacs.example;

interface RefDefaultOverrideContract {
    default void m() {}
}

class RefDefaultOverrideImpl implements RefDefaultOverrideContract {
    public void m() {}
}

class RefDefaultOverrideUse {
    void run(RefDefaultOverrideImpl c) {
        c.m();
    }
}
