package org.javacs.example;

import java.util.function.Supplier;

public class ConstructorNoArg {
    public ConstructorNoArg() {}

    void use() {
        new ConstructorNoArg();
        Supplier<ConstructorNoArg> s = ConstructorNoArg::new;
    }
}
