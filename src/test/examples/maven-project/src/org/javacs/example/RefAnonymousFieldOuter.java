package org.javacs.example;

public class RefAnonymousFieldOuter {
    private int f;

    Runnable task() {
        return new Runnable() {
            private int f;

            public void run() {
                f = 1;
                int x = RefAnonymousFieldOuter.this.f;
            }
        };
    }
}
