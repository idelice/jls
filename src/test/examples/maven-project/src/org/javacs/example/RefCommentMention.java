package org.javacs.example;

class RefCommentMention {
    int f;

    // f
    int lineComment() {
        return f;
    }

    /* f */
    int blockComment() {
        return f;
    }
}
