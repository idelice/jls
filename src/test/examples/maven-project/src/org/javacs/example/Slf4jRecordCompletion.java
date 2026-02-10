package org.javacs.example;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public record Slf4jRecordCompletion(String value) {}

class Slf4jRecordCompletionUsage {
    void test() {
        var rec = new Slf4jRecordCompletion("x");
        rec.
        Slf4jRecordCompletion.
    }
}
