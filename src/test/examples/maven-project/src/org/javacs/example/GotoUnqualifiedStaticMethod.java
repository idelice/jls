package org.javacs.example;

class GotoUnqualifiedStaticMethod {
    record Inner(int value) {
        static final Inner DEFAULT = create(0);

        static Inner create(int value) {
            return new Inner(value);
        }
    }
}
