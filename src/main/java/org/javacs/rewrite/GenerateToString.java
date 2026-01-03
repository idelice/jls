package org.javacs.rewrite;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Logger;
import javax.lang.model.element.Modifier;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class GenerateToString implements Rewrite {
    final String className;

    public GenerateToString(String className) {
        this.className = className;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        LOG.info("Generate toString for " + className + "...");
        var file = compiler.findTypeDeclaration(className);
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var typeElement = task.task.getElements().getTypeElement(className);
            var typeTree = Trees.instance(task.task).getTree(typeElement);
            if (typeTree == null) {
                return Map.of();
            }
            if (hasToString(task, root, typeTree)) {
                return Map.of();
            }
            var fields = fieldsForToString(typeTree);
            var buf = new StringBuilder();
            buf.append("\n");
            buf.append("@Override\n");
            buf.append("public String toString() {\n");
            if (fields.isEmpty()) {
                buf.append("    return \"")
                        .append(simpleName(className))
                        .append("{}\";\n");
            } else {
                buf.append("    return \"")
                        .append(simpleName(className))
                        .append("{\" +\n");
                for (int i = 0; i < fields.size(); i++) {
                    var f = fields.get(i);
                    var last = i == fields.size() - 1;
                    buf.append("        \"")
                            .append(f.getName())
                            .append("=\" + this.")
                            .append(f.getName());
                    if (!last) {
                        buf.append(" + \", \" +\n");
                    } else {
                        buf.append(" + \"}\";\n");
                    }
                }
            }
            buf.append("}\n");
            var string = buf.toString();
            var indent = EditHelper.indent(task.task, root, typeTree) + 4;
            string = string.replaceAll("\n", "\n" + " ".repeat(indent));
            string = string + "\n\n";
            var insert = insertPoint(task, root, typeTree);
            TextEdit[] edits = {new TextEdit(new Range(insert, insert), string)};
            return Map.of(file, edits);
        }
    }

    private List<VariableTree> fieldsForToString(ClassTree typeTree) {
        var fields = new ArrayList<VariableTree>();
        for (var member : typeTree.getMembers()) {
            if (!(member instanceof VariableTree)) continue;
            var field = (VariableTree) member;
            var flags = field.getModifiers().getFlags();
            if (flags.contains(Modifier.STATIC)) continue;
            fields.add(field);
        }
        return fields;
    }

    private boolean hasToString(CompileTask task, CompilationUnitTree root, ClassTree typeTree) {
        for (var member : typeTree.getMembers()) {
            if (member instanceof MethodTree) {
                var method = (MethodTree) member;
                if (method.getReturnType() == null) continue;
                if (!method.getName().contentEquals("toString")) continue;
                if (method.getParameters() != null && !method.getParameters().isEmpty()) continue;
                if (synthentic(task, root, method)) continue;
                return true;
            }
        }
        return false;
    }

    private boolean synthentic(CompileTask task, CompilationUnitTree root, MethodTree method) {
        return Trees.instance(task.task).getSourcePositions().getStartPosition(root, method) != -1;
    }

    private Position insertPoint(CompileTask task, CompilationUnitTree root, ClassTree typeTree) {
        for (var member : typeTree.getMembers()) {
            if (member.getKind() == Tree.Kind.METHOD) {
                var method = (MethodTree) member;
                if (method.getReturnType() == null) continue;
                LOG.info("...insert toString before " + method.getName());
                return EditHelper.insertBefore(task.task, root, method);
            }
        }
        LOG.info("...insert toString at end of class");
        return EditHelper.insertAtEndOfClass(task.task, root, typeTree);
    }

    private String simpleName(String name) {
        var dot = name.lastIndexOf('.');
        if (dot != -1) {
            return name.substring(dot + 1);
        }
        return name;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
