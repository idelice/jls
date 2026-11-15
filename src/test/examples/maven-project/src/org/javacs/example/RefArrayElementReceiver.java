package org.javacs.example;

class RefArrayElementReceiver {
    void m() {}

    void run(RefArrayElementReceiver[] arr) {
        arr[0].m();
    }
}
