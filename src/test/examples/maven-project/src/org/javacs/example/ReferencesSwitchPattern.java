package org.javacs.example;

public class ReferencesSwitchPattern {
    void use(Object o) {}

    void classify(Object o) {
        switch (o) {
            case String s -> {
                use(s);
                use(s.length());
            }
            default -> {}
        }
    }
}
