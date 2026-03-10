package org.javacs.example;

class GotoSwitchCaseEnum {
    enum MyEnum {
        FOO,
        BAR
    }

    String test(MyEnum value) {
        return switch (value) {
            case FOO -> "foo";
            case BAR -> "bar";
        };
    }
}
