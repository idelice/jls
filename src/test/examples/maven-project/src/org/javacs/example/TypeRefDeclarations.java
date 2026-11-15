package org.javacs.example;

class TypeRefDeclarations extends TypeRefTarget {
    TypeRefTarget field;

    TypeRefTarget method(TypeRefTarget parameter) {
        TypeRefTarget local = parameter;
        TypeRefTarget[] array = null;
        TypeRefBox<TypeRefTarget> box = null;
        return local;
    }
}
