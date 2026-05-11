package org.javacs.lsp;

import java.io.Serializable;

public class Position implements Serializable {
    private static final long serialVersionUID = 1L;
    /** 0-based */
    public int line, character;

    public Position() {}

    public Position(int line, int character) {
        this.line = line;
        this.character = character;
    }

    @Override
    public String toString() {
        return line + "," + character;
    }

    public static final Position NONE = new Position(-1, -1);
}
