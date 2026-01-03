package org.javacs.rewrite;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import javax.lang.model.element.Modifier;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class GenerateGettersSetters implements Rewrite {
    final String className;

    public GenerateGettersSetters(String className) {
        this.className = className;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        LOG.info("Generate getters/setters for " + className + "...");
        var file = compiler.findTypeDeclaration(className);
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var typeElement = task.task.getElements().getTypeElement(className);
            var typeTree = Trees.instance(task.task).getTree(typeElement);
            if (typeTree == null) {
                return Map.of();
            }
            var fields = instanceFields(typeTree);
            var methods = existingMethods(task, root, typeTree);
            var buf = new StringBuilder();
            buf.append("\n");
            for (var f : fields) {
                var getterName = getterName(f);
                var setterName = setterName(f);
                if (!methods.contains(getterName)) {
                    buf.append("public ")
                            .append(f.getType())
                            .append(" ")
                            .append(getterName)
                            .append("() {\n")
                            .append("    return this.")
                            .append(f.getName())
                            .append(";\n")
                            .append("}\n\n");
                }
                var isFinal = f.getModifiers().getFlags().contains(Modifier.FINAL);
                if (!isFinal && !methods.contains(setterName)) {
                    buf.append("public void ")
                            .append(setterName)
                            .append("(")
                            .append(f.getType())
                            .append(" ")
                            .append(f.getName())
                            .append(") {\n")
                            .append("    this.")
                            .append(f.getName())
                            .append(" = ")
                            .append(f.getName())
                            .append(";\n")
                            .append("}\n\n");
                }
            }
            var string = buf.toString();
            if (string.trim().isEmpty()) {
                return Map.of();
            }
            var indent = EditHelper.indent(task.task, root, typeTree) + 4;
            string = string.replaceAll("\n", "\n" + " ".repeat(indent));
            string = string + "\n";
            var insert = insertPoint(task, root, typeTree);
            TextEdit[] edits = {new TextEdit(new Range(insert, insert), string)};
            return Map.of(file, edits);
        }
    }

    private List<VariableTree> instanceFields(ClassTree typeTree) {
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

    private List<String> existingMethods(CompileTask task, CompilationUnitTree root, ClassTree typeTree) {
        var names = new ArrayList<String>();
        var pos = Trees.instance(task.task).getSourcePositions();
        for (var member : typeTree.getMembers()) {
            if (member instanceof MethodTree) {
                var method = (MethodTree) member;
                var start = pos.getStartPosition(root, method);
                if (start == -1) {
                    continue; // synthetic/generated (eg Lombok)
                }
                names.add(method.getName().toString());
            }
        }
        return names;
    }

    private String getterName(VariableTree field) {
        var name = field.getName().toString();
        var isBoolean = field.getType().toString().equalsIgnoreCase("boolean");
        var prefix = isBoolean ? "is" : "get";
        return prefix + capitalize(name);
    }

    private String setterName(VariableTree field) {
        var name = field.getName().toString();
        return "set" + capitalize(name);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private Position insertPoint(CompileTask task, CompilationUnitTree root, ClassTree typeTree) {
        for (var member : typeTree.getMembers()) {
            if (member.getKind() == Tree.Kind.METHOD) {
                var method = (MethodTree) member;
                if (method.getReturnType() == null) continue;
                LOG.info("...insert getters/setters before " + method.getName());
                return EditHelper.insertBefore(task.task, root, method);
            }
        }
        LOG.info("...insert getters/setters at end of class");
        return EditHelper.insertAtEndOfClass(task.task, root, typeTree);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
