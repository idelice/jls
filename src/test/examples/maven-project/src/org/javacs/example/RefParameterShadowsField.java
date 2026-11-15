package org.javacs.example;

public class RefParameterShadowsField {
    private int f;

    void run(int f) {
        use(f);
    }

    int readField() {
        return this.f;
    }

    void use(int n) {}
}
