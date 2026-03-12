package org.javacs.example;

abstract class OverrideHierarchyBase {
    abstract String run(String value);
}

class OverrideHierarchyChild extends OverrideHierarchyBase {
    @Override
    String run(String value) {
        return value;
    }
}

class OverrideHierarchyUse {
    String test(OverrideHierarchyBase base, OverrideHierarchyChild child) {
        return base.run("a") + child.run("b");
    }
}
