package org.javacs.example;

@AnnotationRefType
class AnnotationRefUse {
    @AnnotationRefType
    int field;

    @AnnotationRefType
    AnnotationRefUse() {}

    @AnnotationRefType
    void method(@AnnotationRefType int parameter) {
        @AnnotationRefType int local = parameter;
    }
}
