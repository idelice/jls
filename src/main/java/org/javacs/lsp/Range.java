package org.javacs.lsp;

import java.io.Serializable;

public class Range implements Serializable {
    private static final long serialVersionUID = 1L;
    public Position start, end;

    public Range() {}

    public Range(Position start, Position end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public String toString() {
        return start + "-" + end;
    }

    public static final Range NONE = new Range(Position.NONE, Position.NONE);
}
