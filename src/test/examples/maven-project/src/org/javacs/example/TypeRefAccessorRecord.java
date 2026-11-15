package org.javacs.example;

record TypeRefAccessorRecord(int x) {
    public int x() {
        return 42;
    }
}
