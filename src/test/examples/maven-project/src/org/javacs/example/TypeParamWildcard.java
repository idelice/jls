package org.javacs.example;

class TypeParamWildcard {
    TypeParamWildcardBox<? extends Number> upper;
    TypeParamWildcardBox<? super Integer> lower;
}
