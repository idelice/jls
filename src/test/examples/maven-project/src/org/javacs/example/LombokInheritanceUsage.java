package org.javacs.example;

import lombok.Data;

@Data
class LombokParent {
    private String name;
}

@Data
class LombokChild extends LombokParent {
}

@Data
class LombokHolder {
    private LombokParent parent;
}

public class LombokInheritanceUsage {
    void test() {
        LombokHolder holder = new LombokHolder();
        holder.setParent(new LombokChild());
    }
}
