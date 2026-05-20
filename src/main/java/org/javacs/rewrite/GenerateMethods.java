package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class GenerateMethods implements Rewrite {
    private static final Logger LOG = Logger.getLogger("main");

    final String className;
    final String methodKind;
    final int cursorPosition;
    /** When non-null, only generate methods for fields in this set. */
    final Set<String> fieldFilter;

    public GenerateMethods(String className, String methodKind, int cursorPosition) {
        this(className, methodKind, cursorPosition, null);
    }

    public GenerateMethods(String className, String methodKind, int cursorPosition, Set<String> fieldFilter) {
        this.className = className;
        this.methodKind = methodKind;
        this.cursorPosition = cursorPosition;
        this.fieldFilter = fieldFilter;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var file = compiler.findTypeDeclaration(className);
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var trees = Trees.instance(task.task);
            var typeElement = task.task.getElements().getTypeElement(className);
            var classTree = trees.getTree(typeElement);

            var fields = new ArrayList<VariableTree>();
            for (var member : classTree.getMembers()) {
                if (member instanceof VariableTree) {
                    var field = (VariableTree) member;
                    if (!field.getModifiers().getFlags().contains(Modifier.STATIC)) {
                        fields.add(field);
                    }
                }
            }

            if (fields.isEmpty()) return CANCELLED;

            var buf = new StringBuffer();
            buf.append("\n");

            switch (methodKind) {
                case "constructor":
                    generateConstructor(buf, task, root, classTree, fields, trees, typeElement);
                    break;
                case "getters":
                    generateGetters(buf, task, root, classTree, fields, trees);
                    break;
                case "setters":
                    generateSetters(buf, task, root, classTree, fields, trees);
                    break;
                case "equals":
                    generateEquals(buf, task, root, classTree, fields, trees);
                    break;
                case "hashCode":
                    generateHashCode(buf, task, root, classTree, fields, trees);
                    break;
                case "toString":
                    generateToString(buf, task, root, classTree, fields, trees);
                    break;
                default:
                    return CANCELLED;
            }

            var text = buf.toString();
            if (text.isBlank() || text.equals("\n")) return CANCELLED;

            var indent = EditHelper.indent(task.task, root, classTree) + 4;
            text = text.replaceAll("\n", "\n" + " ".repeat(indent));
            text = text + "\n\n";

            var insert = EditHelper.insertAtEndOfClass(task.task, root, classTree);
            TextEdit[] methodEdit = {new TextEdit(new Range(insert, insert), text)};

            if (methodKind.equals("equals") || methodKind.equals("hashCode")) {
                var needsImport = true;
                for (var imp : root.getImports()) {
                    var qid = imp.getQualifiedIdentifier().toString();
                    if (qid.equals("java.util.Objects") || qid.equals("java.util.*")) {
                        needsImport = false;
                        break;
                    }
                }
                if (needsImport) {
                    var importEdits =
                            AddImport.createTextEdits("java.util.Objects", root, trees.getSourcePositions());
                    var allEdits = new ArrayList<TextEdit>();
                    Collections.addAll(allEdits, importEdits);
                    allEdits.add(methodEdit[0]);
                    return Map.of(file, allEdits.toArray(new TextEdit[0]));
                }
            }

            return Map.of(file, methodEdit);
        }
    }

    private void generateConstructor(
            StringBuffer buf,
            CompileTask task,
            CompilationUnitTree root,
            ClassTree classTree,
            List<VariableTree> fields,
            Trees trees,
            javax.lang.model.element.TypeElement typeElement) {
        if (hasConstructor(classTree)) return;

        var simpleName = typeElement.getSimpleName().toString();
        buf.append("public ").append(simpleName).append("(");

        var params = new StringJoiner(", ");
        for (var f : fields) {
            var typeName = extract(task, root, f.getType());
            params.add(typeName + " " + f.getName());
        }
        buf.append(params).append(") {\n");

        for (var f : fields) {
            buf.append("this.").append(f.getName()).append(" = ").append(f.getName()).append(";\n");
        }
        buf.append("}");
    }

    private void generateGetters(
            StringBuffer buf,
            CompileTask task,
            CompilationUnitTree root,
            ClassTree classTree,
            List<VariableTree> fields,
            Trees trees) {
        var first = true;
        for (var f : fields) {
            if (fieldFilter != null && !fieldFilter.contains(f.getName().toString())) continue;
            var isBool = isBooleanPrimitive(trees, root, f);
            var getter = getterName(f, isBool);

            if (hasMethod(classTree, getter)) continue;
            if (isBool && hasMethod(classTree, "get" + capitalize(f.getName().toString()))) continue;

            if (!first) buf.append("\n\n");
            first = false;

            var typeName = extract(task, root, f.getType());
            if (isBool) {
                buf.append("public boolean ").append(getter).append("() {\n");
            } else {
                buf.append("public ").append(typeName).append(" ").append(getter).append("() {\n");
            }
            buf.append("return ").append(f.getName()).append(";\n");
            buf.append("}");
        }
    }

    private void generateSetters(
            StringBuffer buf,
            CompileTask task,
            CompilationUnitTree root,
            ClassTree classTree,
            List<VariableTree> fields,
            Trees trees) {
        var first = true;
        for (var f : fields) {
            if (fieldFilter != null && !fieldFilter.contains(f.getName().toString())) continue;
            if (f.getModifiers().getFlags().contains(Modifier.FINAL)) continue;

            var setter = setterName(f);
            if (hasMethod(classTree, setter)) continue;

            if (!first) buf.append("\n\n");
            first = false;

            var typeName = extract(task, root, f.getType());
            buf.append("public void ").append(setter).append("(").append(typeName).append(" ").append(f.getName()).append(") {\n");
            buf.append("this.").append(f.getName()).append(" = ").append(f.getName()).append(";\n");
            buf.append("}");
        }
    }

    private void generateEquals(
            StringBuffer buf,
            CompileTask task,
            CompilationUnitTree root,
            ClassTree classTree,
            List<VariableTree> fields,
            Trees trees) {
        if (hasMethod(classTree, "equals")) return;

        var simpleName = classTree.getSimpleName().toString();
        buf.append("public boolean equals(Object o) {\n");
        buf.append("if (this == o) return true;\n");
        buf.append("if (!(o instanceof ").append(simpleName).append(")) return false;\n");
        buf.append(simpleName).append(" that = (").append(simpleName).append(") o;\n");
        buf.append("return ");

        var first = true;
        for (var f : fields) {
            if (!first) buf.append("\n&& ");
            first = false;

            var fType = getFieldTypeMirror(trees, root, f);
            if (fType != null && fType.getKind().isPrimitive()) {
                buf.append(f.getName()).append(" == that.").append(f.getName());
            } else {
                buf.append("Objects.equals(").append(f.getName()).append(", that.").append(f.getName()).append(")");
            }
        }
        buf.append(";\n");
        buf.append("}");
    }

    private void generateHashCode(
            StringBuffer buf,
            CompileTask task,
            CompilationUnitTree root,
            ClassTree classTree,
            List<VariableTree> fields,
            Trees trees) {
        if (hasMethod(classTree, "hashCode")) return;

        buf.append("public int hashCode() {\n");
        buf.append("return Objects.hash(");
        var first = true;
        for (var f : fields) {
            if (!first) buf.append(", ");
            first = false;
            buf.append(f.getName());
        }
        buf.append(");\n");
        buf.append("}");
    }

    private void generateToString(
            StringBuffer buf,
            CompileTask task,
            CompilationUnitTree root,
            ClassTree classTree,
            List<VariableTree> fields,
            Trees trees) {
        if (hasMethod(classTree, "toString")) return;

        buf.append("public String toString() {\n");
        buf.append("return getClass().getSimpleName() + \"{\"");
        var first = true;
        for (var f : fields) {
            if (first) {
                buf.append(" + \"");
            } else {
                buf.append(" + \", ");
            }
            first = false;
            buf.append(f.getName()).append("=\" + ").append(f.getName());
        }
        buf.append(" + \"}\";\n");
        buf.append("}");
    }

    private boolean hasConstructor(ClassTree classTree) {
        for (var member : classTree.getMembers()) {
            if (member instanceof MethodTree) {
                var method = (MethodTree) member;
                if (method.getReturnType() == null) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasMethod(ClassTree classTree, String methodName) {
        for (var member : classTree.getMembers()) {
            if (member instanceof MethodTree) {
                var method = (MethodTree) member;
                if (method.getName().equals(methodName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isBooleanPrimitive(Trees trees, CompilationUnitTree root, VariableTree field) {
        var type = getFieldTypeMirror(trees, root, field);
        return type != null && type.getKind() == TypeKind.BOOLEAN;
    }

    private TypeMirror getFieldTypeMirror(Trees trees, CompilationUnitTree root, VariableTree field) {
        var path = trees.getPath(root, field);
        if (path == null) return null;
        return trees.getTypeMirror(path);
    }

    private String getterName(VariableTree field, boolean isBoolean) {
        var name = field.getName().toString();
        if (isBoolean) {
            return "is" + capitalize(name);
        }
        return "get" + capitalize(name);
    }

    private String setterName(VariableTree field) {
        return "set" + capitalize(field.getName().toString());
    }

    private String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private CharSequence extract(CompileTask task, CompilationUnitTree root, Tree typeTree) {
        try {
            var contents = root.getSourceFile().getCharContent(true);
            var pos = Trees.instance(task.task).getSourcePositions();
            var start = (int) pos.getStartPosition(root, typeTree);
            var end = (int) pos.getEndPosition(root, typeTree);
            return contents.subSequence(start, end);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
