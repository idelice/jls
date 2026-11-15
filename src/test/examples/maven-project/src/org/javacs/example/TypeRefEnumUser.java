package org.javacs.example;

class TypeRefEnumUser {
    TypeRefEnumColor field;

    void use(TypeRefEnumColor parameter) {
        TypeRefEnumColor local = parameter;
        Object literal = TypeRefEnumColor.class;
        switch (local) {
            case RED -> {}
            default -> {}
        }
    }
}
