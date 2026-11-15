package org.javacs.example;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ConstructorReferenceTargetTyped {
    public ConstructorReferenceTargetTyped() {}

    public ConstructorReferenceTargetTyped(int value) {}

    void use() {
        Supplier<ConstructorReferenceTargetTyped> noArg = ConstructorReferenceTargetTyped::new;
        IntFunction<ConstructorReferenceTargetTyped> oneArg = ConstructorReferenceTargetTyped::new;
    }
}
