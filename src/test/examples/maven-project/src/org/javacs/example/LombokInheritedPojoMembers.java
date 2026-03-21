package org.javacs.example;

import lombok.Getter;

@Getter
class LombokInheritedPojoMembers extends LombokInheritedPojoBase {
    void use() {
        inheritedService.perform();
    }
}

@Getter
class LombokInheritedPojoBase {
    LombokInheritedHelperService inheritedService;
}

class LombokInheritedHelperService {
    void perform() {
    }
}
