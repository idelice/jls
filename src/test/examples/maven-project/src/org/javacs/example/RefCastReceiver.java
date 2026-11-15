package org.javacs.example;

class RefCastReceiverBase {
    void m() {}
}

class RefCastReceiverSub extends RefCastReceiverBase {
    void m() {}
}

class RefCastReceiverUse {
    void run(RefCastReceiverBase o) {
        ((RefCastReceiverSub) o).m();
    }
}
