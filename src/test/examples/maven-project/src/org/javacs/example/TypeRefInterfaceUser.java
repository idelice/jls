package org.javacs.example;

class TypeRefInterfaceUser implements TypeRefInterface {
    TypeRefInterface field;

    Object cast(Object o) {
        TypeRefInterface local = null;
        Object cast = (TypeRefInterface) o;
        Object literal = TypeRefInterface.class;
        return local;
    }
}
