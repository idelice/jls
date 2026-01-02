package org.javacs.lens;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.tools.Diagnostic;
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
        referencesLens(t, t.getSimpleName().toString()).ifPresent(list::add);
        if (isTestClass(t)) {
            list.add(runAllTests(t));
        }
        var result = super.visitClass(t, list);
        qualifiedName.remove(qualifiedName.size() - 1);
        return result;
    }

    @Override
    public Void visitMethod(MethodTree t, List<CodeLens> list) {
        referencesLens(t, t.getName().toString()).ifPresent(list::add);
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

    private java.util.Optional<CodeLens> referencesLens(Tree t, String name) {
        if (root == null || name == null || name.isBlank()) return java.util.Optional.empty();
        var pos = namePosition(t, name);
        if (pos == null) return java.util.Optional.empty();
        var data = new JsonObject();
        data.addProperty("uri", root.getSourceFile().toUri().toString());
        data.addProperty("line", pos.line);
        data.addProperty("column", pos.character);
        data.addProperty("name", name);
        var posRange = new Range(new Position(pos.line, pos.character), new Position(pos.line, pos.character));
        return java.util.Optional.of(new CodeLens(posRange, null, data));
    }

    private Position namePosition(Tree t, String name) {
        try {
            var source = root.getSourceFile().getCharContent(true);
            var positions = Trees.instance(task).getSourcePositions();
            var start = positions.getStartPosition(root, t);
            var end = positions.getEndPosition(root, t);
            if (start == Diagnostic.NOPOS || end == Diagnostic.NOPOS || end <= start) {
                return null;
            }
            var windowEnd = Math.min((int) end, (int) start + 300);
            var snippet = source.subSequence((int) start, windowEnd).toString();
            var idx = findIdentifier(snippet, name);
            if (idx < 0) return null;
            var offset = (int) start + idx;
            var lines = root.getLineMap();
            var line = (int) lines.getLineNumber(offset) - 1;
            var column = (int) lines.getColumnNumber(offset) - 1;
            return new Position(line, column);
        } catch (Exception e) {
            return null;
        }
    }

    private int findIdentifier(String text, String name) {
        var idx = text.indexOf(name);
        while (idx >= 0) {
            var beforeOk = idx == 0 || !Character.isJavaIdentifierPart(text.charAt(idx - 1));
            var afterIdx = idx + name.length();
            var afterOk = afterIdx >= text.length()
                    || !Character.isJavaIdentifierPart(text.charAt(afterIdx));
            if (beforeOk && afterOk) return idx;
            idx = text.indexOf(name, idx + 1);
        }
        return -1;
    }
}
