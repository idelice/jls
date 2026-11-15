package org.javacs.example;

public class ReferencesOuterThis {
    private int f;

    class Inner {
        private int f;

        int readInner() {
            return f;
        }

        int readOuter() {
            return ReferencesOuterThis.this.f;
        }
    }
}
