package org.javacs.example;

class AutocompleteCasePartial {
    void test() {
        switch (myEnum()) {
            case F
        }
    }

    MyEnum myEnum() {
        return MyEnum.Foo;
    }

    enum MyEnum {
        Foo,
        Bar
    }
}