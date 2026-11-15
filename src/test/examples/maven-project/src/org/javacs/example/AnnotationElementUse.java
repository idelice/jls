package org.javacs.example;

@AnnotationElementDef("shorthand")
class AnnotationElementUse {
    @AnnotationElementDef(value = "explicit")
    int field;
}
