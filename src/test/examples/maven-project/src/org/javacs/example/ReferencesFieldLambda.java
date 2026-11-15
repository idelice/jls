package org.javacs.example;

public class ReferencesFieldLambda {
    private int f;

    Runnable task() {
        return () -> {
            f = 1;
            int x = this.f + f;
            use(x);
        };
    }

    void use(int n) {}
}
