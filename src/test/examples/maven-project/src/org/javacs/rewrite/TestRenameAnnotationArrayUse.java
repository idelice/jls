package org.javacs.rewrite;

@TestRenameAnnotationArray({"a", "b"})
class TestRenameAnnotationArrayUse {
    @TestRenameAnnotationArray({})
    int field;
}
