package org.javacs.example;

interface RefIfacePrivateContract {
    private void h() {}

    default void d() {
        h();
    }
}
