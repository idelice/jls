package org.javacs.example;

public record RecordCompactConstructor(int x) {
    public RecordCompactConstructor {
    }

    static RecordCompactConstructor create() {
        return new RecordCompactConstructor(1);
    }

    static RecordCompactConstructor createOther() {
        return new RecordCompactConstructor(2);
    }
}
