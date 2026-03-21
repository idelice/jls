package org.javacs.example;

public record RecordSymbols(String id, int count) {
    public String format() {
        return id + ":" + count;
    }
}
