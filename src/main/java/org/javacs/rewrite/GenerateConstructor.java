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
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class GenerateConstructor implements Rewrite {
    final String className;
    final List<Pattern> includePatterns;

    public GenerateConstructor(String className) {
        this.className = className;
        this.includePatterns = List.of();
    }

    public GenerateConstructor(String className, List<Pattern> includePatterns) {
        this.className = className;
        this.includePatterns = includePatterns == null ? List.of() : includePatterns;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        LOG.info("Generate constructor for " + className + "...");
        var file = compiler.findTypeDeclaration(className);
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var typeElement = task.task.getElements().getTypeElement(className);
            var typeTree = Trees.instance(task.task).getTree(typeElement);
            if (typeTree == null) {
                return Map.of();
            }
            if (hasConstructor(task, root, typeTree)) {
                return Map.of();
            }
            var fields = fieldsForConstructor(typeTree);
            var parameters = generateParameters(task, root, fields);
            var initializers = generateInitializers(fields);
            var buf = new StringBuffer();
            buf.append("\n");
            if (typeTree.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                buf.append("public ");
            }
            buf.append(simpleName(className))
                    .append("(")
                    .append(parameters)
                    .append(") {\n");
            if (!initializers.isEmpty()) {
                buf.append("    ").append(initializers).append("\n");
            }
            buf.append("}");
            var string = buf.toString();
            var indent = EditHelper.indent(task.task, root, typeTree) + 4;
            string = string.replaceAll("\n", "\n" + " ".repeat(indent));
            string = string + "\n\n";
            var insert = insertPoint(task, root, typeTree);
            TextEdit[] edits = {new TextEdit(new Range(insert, insert), string)};
            return Map.of(file, edits);
        }
    }

    private List<VariableTree> fieldsForConstructor(ClassTree typeTree) {
        var fields = new ArrayList<VariableTree>();
        for (var member : typeTree.getMembers()) {
            if (!(member instanceof VariableTree)) continue;
            var field = (VariableTree) member;
            if (field.getInitializer() != null) continue;
            var flags = field.getModifiers().getFlags();
            if (flags.contains(Modifier.STATIC)) continue;
            if (!matchesFieldName(field.getName().toString())) continue;
            fields.add(field);
        }
        return fields;
    }

    private boolean matchesFieldName(String fieldName) {
        if (!includePatterns.isEmpty()) {
            var allowed = false;
            for (var p : includePatterns) {
                if (p.matcher(fieldName).find()) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) return false;
        }
        return true;
    }

    private String generateParameters(CompileTask task, CompilationUnitTree root, List<VariableTree> fields) {
        var join = new StringJoiner(", ");
        for (var f : fields) {
            join.add(extract(task, root, f.getType()) + " " + f.getName());
        }
        return join.toString();
    }

    private String generateInitializers(List<VariableTree> fields) {
        var join = new StringJoiner("\n    ");
        for (var f : fields) {
            join.add("this." + f.getName() + " = " + f.getName() + ";");
        }
        return join.toString();
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

    private String simpleName(String className) {
        var dot = className.lastIndexOf('.');
        if (dot != -1) {
            return className.substring(dot + 1);
        }
        return className;
    }

    private boolean hasConstructor(CompileTask task, CompilationUnitTree root, ClassTree typeTree) {
        for (var member : typeTree.getMembers()) {
            if (member instanceof MethodTree) {
                var method = (MethodTree) member;
                if (method.getReturnType() == null && !synthentic(task, root, method)) {
                    return true;
                }
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
                LOG.info("...insert constructor before " + method.getName());
                return EditHelper.insertBefore(task.task, root, method);
            }
        }
        LOG.info("...insert constructor at end of class");
        return EditHelper.insertAtEndOfClass(task.task, root, typeTree);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
