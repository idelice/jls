package org.javacs.lens;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.javacs.FileStore;
import org.javacs.lsp.CodeLens;
import org.javacs.lsp.Command;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

class FindCodeLenses extends TreeScanner<Void, List<CodeLens>> {
    private final JavacTask task;
    private CompilationUnitTree root;
    private List<CharSequence> qualifiedName = new ArrayList<>();

    FindCodeLenses(JavacTask task) {
        this.task = task;
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree t, List<CodeLens> list) {
        var name = Objects.toString(t.getPackageName(), "");
        qualifiedName.add(name);
        root = t;
        return super.visitCompilationUnit(t, list);
    }

    @Override
    public Void visitClass(ClassTree t, List<CodeLens> list) {
        qualifiedName.add(t.getSimpleName());
        if (isTestClass(t)) {
            list.add(runAllTests(t));
        }
        var result = super.visitClass(t, list);
        qualifiedName.remove(qualifiedName.size() - 1);
        return result;
    }

    @Override
    public Void visitMethod(MethodTree t, List<CodeLens> list) {
        if (isTestMethod(t)) {
            list.add(runTest(t));
            list.add(debugTest(t));
        }
        return super.visitMethod(t, list);
    }

    private boolean isTestClass(ClassTree t) {
        for (var member : t.getMembers()) {
            if (!(member instanceof MethodTree)) continue;
            var method = (MethodTree) member;
            if (isTestMethod(method)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTestMethod(MethodTree t) {
        for (var ann : t.getModifiers().getAnnotations()) {
            var type = ann.getAnnotationType();
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                var name = id.getName();
                if (name.contentEquals("Test") || name.contentEquals("org.junit.Test")) {
                    return true;
                }
            }
        }
        return false;
    }

    private CodeLens runAllTests(ClassTree t) {
        var arguments = new JsonArray();
        arguments.add(root.getSourceFile().toUri().toString());
        arguments.add(String.join(".", qualifiedName));
        arguments.add(JsonNull.INSTANCE);
        var command = new Command("Run All Tests", "java.command.test.run", arguments);
        var range = range(t);
        return new CodeLens(range, command, null);
    }

    private CodeLens runTest(MethodTree t) {
        var arguments = new JsonArray();
        arguments.add(root.getSourceFile().toUri().toString());
        arguments.add(String.join(".", qualifiedName));
        arguments.add(t.getName().toString());
        var command = new Command("Run Test", "java.command.test.run", arguments);
        var range = range(t);
        return new CodeLens(range, command, null);
    }

    private CodeLens debugTest(MethodTree t) {
        var arguments = new JsonArray();
        arguments.add(root.getSourceFile().toUri().toString());
        arguments.add(String.join(".", qualifiedName));
        arguments.add(t.getName().toString());
        var sourceRoots = new JsonArray();
        for (var dir : FileStore.sourceRoots()) {
            sourceRoots.add(dir.toString());
        }
        arguments.add(sourceRoots);
        var command = new Command("Debug Test", "java.command.test.debug", arguments);
        var range = range(t);
        return new CodeLens(range, command, null);
    }

    private Range range(Tree t) {
        var pos = Trees.instance(task).getSourcePositions();
        var lines = root.getLineMap();
        var start = pos.getStartPosition(root, t);
        var end = pos.getEndPosition(root, t);
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        return new Range(new Position(startLine - 1, startColumn - 1), new Position(endLine - 1, endColumn - 1));
    }
}
