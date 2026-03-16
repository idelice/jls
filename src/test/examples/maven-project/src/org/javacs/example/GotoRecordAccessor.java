package org.javacs.example;

record GotoRecordAccessor(String foo) {
    void use() {
        foo();
    }
}
