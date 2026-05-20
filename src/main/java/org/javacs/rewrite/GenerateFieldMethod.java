package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Path;
import java.util.*;
import javax.lang.model.element.Modifier;
import org.javacs.CompilerProvider;
import org.javacs.lsp.*;

public class GenerateFieldMethod implements Rewrite {
    final String className;
    final String methodKind; // "getters" or "setters"
    final String fieldName;
    final int cursorPosition;

    public GenerateFieldMethod(String className, String methodKind, String fieldName, int cursorPosition) {
        this.className = className;
        this.methodKind = methodKind;
        this.fieldName = fieldName;
        this.cursorPosition = cursorPosition;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var file = compiler.findTypeDeclaration(className);
        if (file == CompilerProvider.NOT_FOUND) {
            return CANCELLED;
        }
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            if (root == null) {
                return CANCELLED;
            }
            var trees = Trees.instance(task.task);
            var typeElement = task.task.getElements().getTypeElement(className);
            if (typeElement == null) {
                return CANCELLED;
            }
            var classTree = trees.getTree(typeElement);
            if (classTree == null) {
                return CANCELLED;
            }

            // Find the specific field
            VariableTree targetField = null;
            for (var member : classTree.getMembers()) {
                if (member instanceof VariableTree) {
                    var field = (VariableTree) member;
                    if (field.getName().toString().equals(fieldName)) {
                        targetField = field;
                        break;
                    }
                }
            }
            if (targetField == null) return CANCELLED;
            if (targetField.getModifiers().getFlags().contains(Modifier.STATIC)) return CANCELLED;
            if (methodKind.equals("setters")
                    && targetField.getModifiers().getFlags().contains(Modifier.FINAL)) {
                return CANCELLED;
            }

            // Check if getter/setter already exists
            var cap = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            var getterName = (methodKind.equals("getters") ? "get" : "set") + cap;
            for (var member : classTree.getMembers()) {
                if (member instanceof MethodTree) {
                    var method = (MethodTree) member;
                    if (method.getName().toString().equals(getterName)) return CANCELLED;
                }
            }

            // Build field type name
            var typeName = targetField.getType().toString();

            var buf = new StringBuffer();
            buf.append("\n");
            if (methodKind.equals("getters")) {
                buf.append("public ").append(typeName).append(" ").append(getterName).append("() {\n");
                buf.append("return ").append(fieldName).append(";\n");
                buf.append("}");
            } else {
                buf.append("public void ")
                        .append(getterName)
                        .append("(")
                        .append(typeName)
                        .append(" ")
                        .append(fieldName)
                        .append(") {\n");
                buf.append("this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
                buf.append("}");
            }

            var indent = EditHelper.indent(task.task, root, classTree) + 4;
            var text = buf.toString().replaceAll("\n", "\n" + " ".repeat(indent));
            text = text + "\n\n";
            var insert = EditHelper.insertAtEndOfClass(task.task, root, classTree);
            return Map.of(file, new TextEdit[] {new TextEdit(new Range(insert, insert), text)});
        }
    }
}
