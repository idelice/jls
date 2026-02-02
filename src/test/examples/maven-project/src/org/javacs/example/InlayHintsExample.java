package org.javacs.example;

public class InlayHintsExample {
    void call() {
        take("name", 42);
        new InlayHintsExample().take("other", 7);
        var message = "hello";
        var size = 3;
    }

    void take(String label, int count) {}
}
