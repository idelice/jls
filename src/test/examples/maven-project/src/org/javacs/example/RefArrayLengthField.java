package org.javacs.example;

class RefArrayLengthField {
    private int length;

    int arrayLength(int[] a) {
        return a.length;
    }

    int fieldUses() {
        return length + this.length;
    }
}
