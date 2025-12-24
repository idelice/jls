package org.javacs.index;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.List;
import java.util.Objects;
import org.javacs.ParseTask;
import org.javacs.StringSearch;
import org.javacs.lsp.Location;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.SymbolInformation;
import org.javacs.lsp.SymbolKind;

class FindSymbolsMatching extends TreePathScanner<Void, List<SymbolInformation>> {

    private final ParseTask task;
    private final String query;
    private CompilationUnitTree root;
    private CharSequence containerName;

    FindSymbolsMatching(ParseTask task, String query) {
        this.task = task;
        this.query = query;
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree t, List<SymbolInformation> list) {
        root = t;
        containerName = Objects.toString(t.getPackageName(), "");
        return super.visitCompilationUnit(t, list);
    }

    @Override
    public Void visitClass(ClassTree t, List<SymbolInformation> list) {
        if (StringSearch.matchesTitleCase(t.getSimpleName(), query)) {
            var info = new SymbolInformation();
            info.name = t.getSimpleName().toString();
            info.kind = asSymbolKind(t.getKind());
            info.location = location(t);
            info.containerName = containerName.toString();
            list.add(info);
        }
        var push = containerName;
        containerName = t.getSimpleName();
        super.visitClass(t, list);
        containerName = push;
        return null;
    }

    @Override
    public Void visitMethod(MethodTree t, List<SymbolInformation> list) {
        if (StringSearch.matchesTitleCase(t.getName(), query)) {
            var info = new SymbolInformation();
            info.name = t.getName().toString();
            info.kind = asSymbolKind(t.getKind());
            info.location = location(t);
            info.containerName = containerName.toString();
            list.add(info);
        }
        var push = containerName;
        containerName = t.getName();
        super.visitMethod(t, list);
        containerName = push;
        return null;
    }

    @Override
    public Void visitVariable(VariableTree t, List<SymbolInformation> list) {
        if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree
                && StringSearch.matchesTitleCase(t.getName(), query)) {
            var info = new SymbolInformation();
            info.name = t.getName().toString();
            info.kind = asSymbolKind(t.getKind());
            info.location = location(t);
            info.containerName = containerName.toString();
            list.add(info);
        }
        var push = containerName;
        containerName = t.getName();
        super.visitVariable(t, list);
        containerName = push;
        return null;
    }

    private static Integer asSymbolKind(Tree.Kind k) {
        switch (k) {
            case ANNOTATION_TYPE:
            case CLASS:
                return SymbolKind.Class;
            case ENUM:
                return SymbolKind.Enum;
            case INTERFACE:
                return SymbolKind.Interface;
            case METHOD:
                return SymbolKind.Method;
            case TYPE_PARAMETER:
                return SymbolKind.TypeParameter;
            case VARIABLE:
                // This method is used for symbol-search functionality,
                // where we only return fields, not local variables
                return SymbolKind.Field;
            default:
                return null;
        }
    }

    private Location location(Tree t) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var lines = task.root.getLineMap();
        var start = pos.getStartPosition(root, t);
        var end = pos.getEndPosition(root, t);
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        var range = new Range(new Position(startLine - 1, startColumn - 1), new Position(endLine - 1, endColumn - 1));
        return new Location(root.getSourceFile().toUri(), range);
    }
}
