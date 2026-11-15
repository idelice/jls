package org.javacs.example;

public record RecordCanonicalConstructor(int x) {
    static RecordCanonicalConstructor first() {
        return new RecordCanonicalConstructor(1);
    }

    static RecordCanonicalConstructor second() {
        return new RecordCanonicalConstructor(2);
    }
}
