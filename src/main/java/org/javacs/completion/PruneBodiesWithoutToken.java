package org.javacs.completion;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;

public class PruneBodiesWithoutToken extends TreeScanner<StringBuilder, String> {
    private final JavacTask task;
    private final StringBuilder buf = new StringBuilder();
    private CompilationUnitTree root;

    public PruneBodiesWithoutToken(JavacTask task) {
        this.task = task;
    }

    @Override
    public StringBuilder visitCompilationUnit(CompilationUnitTree t, String token) {
        root = t;
        try {
            var contents = t.getSourceFile().getCharContent(true);
            buf.setLength(0);
            buf.append(contents);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        super.visitCompilationUnit(t, token);
        return buf;
    }

    @Override
    public StringBuilder visitMethod(MethodTree t, String token) {
        var pos = Trees.instance(task).getSourcePositions();
        if (t.getBody() == null) return buf;
        var start = (int) pos.getStartPosition(root, t.getBody());
        var end = (int) pos.getEndPosition(root, t.getBody());
        if (start < 0 || end < 0) return buf;
        if (bodyContainsToken(start, end, token)) return buf;
        for (var i = start + 1; i < end - 1; i++) {
            if (!Character.isWhitespace(buf.charAt(i))) {
                buf.setCharAt(i, ' ');
            }
        }
        return buf;
    }

    private boolean bodyContainsToken(int start, int end, String token) {
        var index = buf.indexOf(token, start);
        return index >= 0 && index < end;
    }

    @Override
    public StringBuilder reduce(StringBuilder a, StringBuilder b) {
        return buf;
    }
}
