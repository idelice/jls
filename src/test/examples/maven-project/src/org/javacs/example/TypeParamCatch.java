package org.javacs.example;

class TypeParamCatch {
    void m(Object o) {
        try {
            o.toString();
        } catch (TypeParamCatchException e) {
            e.toString();
        }
    }
}
