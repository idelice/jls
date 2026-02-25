package org.javacs.example;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@AllArgsConstructor
@Getter
enum EnumSetterOverloadType {
    A(new Bar("x"));

    private final Bar type;

    public static Bar getType(Bar bar) {
        return bar;
    }

    public static Bar getType(String value) {
        return new Bar(value);
    }

    static final class Bar {
        final String value;

        Bar(String value) {
            this.value = value;
        }
    }
}

@AllArgsConstructor
@Setter
class EnumSetterOverloadHolder {
    private EnumSetterOverloadType.Bar value;
}

public class LombokEnumSetterOverload {
    void test() {
        var foo = new EnumSetterOverloadHolder(null);
        foo.setValue(EnumSetterOverloadType.A.getType());
    }
}
