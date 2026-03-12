package org.javacs.example;

import lombok.Getter;

@Getter
class LombokInheritedAccessorBase {
    private String name;
}

class LombokInheritedAccessorChild extends LombokInheritedAccessorBase {
}

class LombokInheritedAccessorUse {
    void use(LombokInheritedAccessorChild foo) {
        foo.getName();
    }
}
