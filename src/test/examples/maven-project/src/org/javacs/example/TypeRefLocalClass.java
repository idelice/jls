package org.javacs.example;

class TypeRefLocalClass {
    void use() {
        class Local {}

        Local value = new Local();
        Object plain = value;
    }
}
