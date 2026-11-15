package org.javacs.example;

public class RefLocalShadowsField {
    private int f;

    void run() {
        int f = 1;
        use(f);
    }

    int readField() {
        return this.f;
    }

    void use(int n) {}
}
