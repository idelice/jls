package org.javacs.example;

public class VarRedundantHints {
    static class Foo {}

    void run() {
        var ctor = new Foo();
        var array = new String[] {"x"};
        var casted = (Object) "x";
        var clazz = String.class;
    }
}
