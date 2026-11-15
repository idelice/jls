package org.javacs.example;

class TypeRefRecordUser {
    TypeRefRecordTarget field;

    Object make() {
        TypeRefRecordTarget local = new TypeRefRecordTarget(1);
        return TypeRefRecordTarget.class;
    }
}
