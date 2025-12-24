package org.javacs.example;

class GotoOverloadInOtherFile {
    void main() {
        GotoOverload.overloaded = 10;
        GotoOverload.overloaded(1);
        GotoOverload.overloaded("1");
    }
}