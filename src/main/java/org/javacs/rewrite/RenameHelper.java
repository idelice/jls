package org.javacs.rewrite;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import org.javacs.CompileTask;
import org.javacs.FindHelper;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

class RenameHelper {
    final CompileTask task;

    RenameHelper(CompileTask task) {
        this.task = task;
    }

    TextEdit[] renameVariable(CompilationUnitTree root, TreePath rename, String newName) {
        var trees = Trees.instance(task.task);
        var target = trees.getElement(rename);
        var found = findVariableReferences(root, target);
        return replaceAll(found, newName);
    }

    Map<Path, TextEdit[]> renameMethod(
            List<CompilationUnitTree> roots,
            String className,
            String methodName,
            String[] erasedParameterTypes,
            String newName) {
        var allEdits = new HashMap<Path, TextEdit[]>();
        var method = FindHelper.findMethod(task, className, methodName, erasedParameterTypes);
        for (var root : roots) {
            var file = Paths.get(root.getSourceFile().toUri());
            var references = findMethodReferences(root, method);
            if (references.isEmpty()) continue;
            var fileEdits = replaceAll(references, newName);
            allEdits.put(file, fileEdits);
        }
        return allEdits;
    }

    Map<Path, TextEdit[]> renameField(
            List<CompilationUnitTree> roots, String className, String fieldName, String newName) {
        var allEdits = new HashMap<Path, TextEdit[]>();
        for (var root : roots) {
            var file = Paths.get(root.getSourceFile().toUri());
            var references = findFieldReferences(root, className, fieldName);
            if (references.isEmpty()) continue;
            var fileEdits = replaceAll(references, newName);
            allEdits.put(file, fileEdits);
        }
        return allEdits;
    }

    private List<TreePath> findVariableReferences(CompilationUnitTree root, Element target) {
        var trees = Trees.instance(task.task);
        var found = new ArrayList<TreePath>();
        Consumer<TreePath> forEach =
                path -> {
                    var candidate = trees.getElement(path);
                    if (target.equals(candidate)) {
                        found.add(path);
                    }
                };
        new FindReferences().scan(root, forEach);
        return found;
    }

    private List<TreePath> findFieldReferences(CompilationUnitTree root, String className, String fieldName) {
        var found = new ArrayList<TreePath>();
        Consumer<TreePath> forEach =
                path -> {
                    if (isFieldReference(path, className, fieldName)) {
                        found.add(path);
                    }
                };
        new FindFieldReferences().scan(root, forEach);
        return found;
    }

    private boolean isFieldReference(TreePath path, String className, String fieldName) {
        var trees = Trees.instance(task.task);
        var candidate = trees.getElement(path);
        if (!(candidate instanceof VariableElement)) return false;
        var variable = (VariableElement) candidate;
        if (!variable.getSimpleName().contentEquals(fieldName)) return false;
        if (!(variable.getEnclosingElement() instanceof TypeElement)) return false;
        var parent = (TypeElement) variable.getEnclosingElement();
        if (!parent.getQualifiedName().contentEquals(className)) return false;
        return true;
    }

    private List<TreePath> findMethodReferences(CompilationUnitTree root, ExecutableElement find) {
        var trees = Trees.instance(task.task);
        var found = new ArrayList<TreePath>();
        Consumer<TreePath> forEach =
                path -> {
                    var candidate = trees.getElement(path);
                    if (find.equals(candidate)) {
                        found.add(path);
                    }
                };
        new FindMethodReferences().scan(root, forEach);
        return found;
    }

    private TextEdit[] replaceAll(List<TreePath> found, String newName) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var edits = new TextEdit[found.size()];
        var i = 0;
        for (var f : found) {
            var root = f.getCompilationUnit();
            var lines = root.getLineMap();
            var startPos = pos.getStartPosition(root, f.getLeaf());
            var endPos = pos.getEndPosition(root, f.getLeaf());
            if (f.getLeaf() instanceof VariableTree) {
                var variable = (VariableTree) f.getLeaf();
                startPos = findName(root, startPos, variable.getName());
                endPos = startPos + variable.getName().length();
            }
            if (f.getLeaf() instanceof MethodTree) {
                var method = (MethodTree) f.getLeaf();
                startPos = pos.getEndPosition(root, method.getReturnType());
                startPos = findName(root, startPos, method.getName());
                endPos = startPos + method.getName().length();
            }
            if (f.getLeaf() instanceof MemberReferenceTree) {
                var select = (MemberReferenceTree) f.getLeaf();
                startPos = pos.getEndPosition(root, select.getQualifierExpression());
                startPos = findName(root, startPos, select.getName());
                endPos = startPos + select.getName().length();
            }
            if (f.getLeaf() instanceof MemberSelectTree) {
                var select = (MemberSelectTree) f.getLeaf();
                startPos = pos.getEndPosition(root, select.getExpression());
                startPos = findName(root, startPos, select.getIdentifier());
                endPos = startPos + select.getIdentifier().length();
            }
            var startLine = (int) lines.getLineNumber(startPos);
            var startColumn = (int) lines.getColumnNumber(startPos);
            var endLine = (int) lines.getLineNumber(endPos);
            var endColumn = (int) lines.getColumnNumber(endPos);
            var range =
                    new Range(new Position(startLine - 1, startColumn - 1), new Position(endLine - 1, endColumn - 1));
            edits[i++] = new TextEdit(range, newName);
        }
        return edits;
    }

    private long findName(CompilationUnitTree root, long startPos, CharSequence name) {
        try {
            var contents = root.getSourceFile().getCharContent(true);
            var matcher = Pattern.compile("\\b" + name + "\\b").matcher(contents);
            if (matcher.find((int) startPos)) {
                return matcher.start();
            }
            return startPos;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
