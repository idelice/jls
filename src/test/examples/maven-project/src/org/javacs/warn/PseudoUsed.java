package org.javacs.warn;

class PseudoUsed {
    void test() {
        pseudoUsed("1");
    }

    private void pseudoUsed(int x) { }
}