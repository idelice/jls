package org.javacs.example;

class TypeParamBound<T extends Number & Comparable<T>> {
    T value;
}
