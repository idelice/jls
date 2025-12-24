package org.javacs.rewrite;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.Map;
import java.util.StringJoiner;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class CreateMissingMethod implements Rewrite {
    final Path file;
    final int position;

    public CreateMissingMethod(Path file, int position) {
        this.file = file;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var trees = Trees.instance(task.task);
            var call = new FindMethodCallAt(task.task).scan(task.root(), position);
            if (call == null) return CANCELLED;
            var path = trees.getPath(task.root(), call);
            var insertText = "\n" + printMethodHeader(task, call) + " {\n    // TODO\n}";
            var surroundingClass = surroundingClass(path);
            var indent = EditHelper.indent(task.task, task.root(), surroundingClass) + 4;
            insertText = insertText.replaceAll("\n", "\n" + " ".repeat(indent));
            insertText = insertText + "\n";
            var insertPoint = EditHelper.insertAfter(task.task, task.root(), surroundingMethod(path));
            TextEdit[] edits = {new TextEdit(new Range(insertPoint, insertPoint), insertText)};
            return Map.of(file, edits);
        }
    }

    private ClassTree surroundingClass(TreePath call) {
        while (call != null) {
            if (call.getLeaf() instanceof ClassTree) {
                return (ClassTree) call.getLeaf();
            }
            call = call.getParentPath();
        }
        throw new RuntimeException("No surrounding class");
    }

    private MethodTree surroundingMethod(TreePath call) {
        while (call != null) {
            if (call.getLeaf() instanceof MethodTree) {
                return (MethodTree) call.getLeaf();
            }
            call = call.getParentPath();
        }
        throw new RuntimeException("No surrounding class");
    }

    private String printMethodHeader(CompileTask task, MethodInvocationTree call) {
        var methodName = extractMethodName(call.getMethodSelect());
        var returnType = "void"; // TODO infer type
        if (returnType.equals(methodName)) {
            returnType = "_";
        }
        var parameters = printParameters(task, call);
        return "private " + returnType + " " + methodName + "(" + parameters + ")";
    }

    private String printParameters(CompileTask task, MethodInvocationTree call) {
        var trees = Trees.instance(task.task);
        var join = new StringJoiner(", ");
        for (var i = 0; i < call.getArguments().size(); i++) {
            var type = trees.getTypeMirror(trees.getPath(task.root(), call.getArguments().get(i)));
            var name = guessParameterName(call.getArguments().get(i), type);
            var printType = EditHelper.printType(type);
            join.add(printType + " " + name);
        }
        return join.toString();
    }

    private String extractMethodName(ExpressionTree method) {
        if (method instanceof IdentifierTree) {
            var id = (IdentifierTree) method;
            return id.getName().toString();
        } else if (method instanceof MemberSelectTree) {
            var select = (MemberSelectTree) method;
            return select.getIdentifier().toString();
        } else {
            return "_";
        }
    }

    private String guessParameterName(Tree argument, TypeMirror type) {
        var fromTree = guessParameterNameFromTree(argument);
        if (!fromTree.isEmpty()) {
            return fromTree;
        }
        var fromType = guessParameterNameFromType(type);
        if (!fromType.isEmpty()) {
            return fromType;
        }
        return "_";
    }

    private String guessParameterNameFromTree(Tree argument) {
        if (argument instanceof IdentifierTree) {
            var id = (IdentifierTree) argument;
            return id.getName().toString();
        } else if (argument instanceof MemberSelectTree) {
            var select = (MemberSelectTree) argument;
            return select.getIdentifier().toString();
        } else if (argument instanceof MemberReferenceTree) {
            var reference = (MemberReferenceTree) argument;
            return reference.getName().toString();
        } else {
            return "";
        }
    }

    private String guessParameterNameFromType(TypeMirror type) {
        if (type instanceof DeclaredType) {
            var declared = (DeclaredType) type;
            var name = declared.asElement().getSimpleName();
            return "" + Character.toLowerCase(name.charAt(0)) + name.subSequence(1, name.length());
        } else {
            return "";
        }
    }
}
