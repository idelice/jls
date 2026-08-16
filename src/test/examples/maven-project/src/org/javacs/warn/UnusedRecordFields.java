package org.javacs.warn;

class UnusedRecordFields {
    // Record fields should NOT produce unused_field warnings
    record Config(String host, int port, String protocol) {}

    // But a private field in a normal class should still warn
    private int unusedClassField;

    void use() {
        var c = new Config("localhost", 8080, "http");
        System.out.println(c.host());
    }
}
