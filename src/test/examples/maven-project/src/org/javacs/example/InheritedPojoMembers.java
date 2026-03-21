package org.javacs.example;

public class InheritedPojoMembers extends InheritedPojoBase {
    void use() {
        inheritedService.perform();
    }
}

class InheritedPojoBase {
    HelperService inheritedService;
}

class HelperService {
    void perform() {
    }
}
