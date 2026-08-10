package org.javacs.rewrite;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.LombokAnnotations;
import org.javacs.lsp.TextEdit;

public class RenameField implements Rewrite {
    final String className, fieldName, newName;

    public RenameField(String className, String fieldName, String newName) {
        this.className = className;
        this.fieldName = fieldName;
        this.newName = newName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        LOG.info("Rewrite " + className + "#" + fieldName + " to " + newName + "...");
        var accessorRenames = lombokAccessorRenames(compiler);
        var paths = new LinkedHashSet<Path>();
        paths.addAll(Arrays.asList(compiler.findMemberReferences(className, fieldName)));
        for (var accessor : accessorRenames.keySet()) {
            paths.addAll(Arrays.asList(compiler.findMemberReferences(className, accessor)));
        }
        if (paths.isEmpty()) {
            LOG.warning("...no references to " + className + "#" + fieldName);
            return Map.of();
        }
        LOG.info("...check " + paths.size() + " files for references");
        try (var compile = compiler.compile(paths.toArray(Path[]::new))) {
            var helper = new RenameHelper(compile);
            var edits = helper.renameField(
                    compile.roots, className, fieldName, newName, accessorRenames);
            return edits;
        }
    }

    private Map<String, String> lombokAccessorRenames(CompilerProvider compiler) {
        var sourceFile = compiler.findTypeDeclaration(className);
        if (sourceFile == CompilerProvider.NOT_FOUND) return Map.of();
        if (!LombokAnnotations.hasStructuralLombokAnnotation(compiler.parse(sourceFile).root())) {
            return Map.of();
        }
        try (var task = compiler.compile(sourceFile)) {
            var owner = task.elements.getTypeElement(className);
            if (owner == null || !(task.trees.getTree(owner) instanceof ClassTree declaration)) {
                return Map.of();
            }
            for (var member : declaration.getMembers()) {
                if (!(member instanceof VariableTree field)
                        || !field.getName().contentEquals(fieldName)) continue;
                var oldAccessors = LombokAnnotations.accessorInfo(
                        declaration.getModifiers(), field.getModifiers(), fieldName, field.getType().toString());
                var newAccessors = LombokAnnotations.accessorInfo(
                        declaration.getModifiers(), field.getModifiers(), newName, field.getType().toString());
                if (oldAccessors.isEmpty() || newAccessors.isEmpty()) return Map.of();
                var result = new LinkedHashMap<String, String>();
                if (oldAccessors.get().hasGetter()
                        && !declaresMethod(declaration, oldAccessors.get().getterName(), 0)) {
                    result.put(oldAccessors.get().getterName(), newAccessors.get().getterName());
                }
                if (oldAccessors.get().hasSetter()
                        && !declaresMethod(declaration, oldAccessors.get().setterName(), 1)) {
                    result.put(oldAccessors.get().setterName(), newAccessors.get().setterName());
                }
                return result;
            }
        }
        return Map.of();
    }

    private boolean declaresMethod(ClassTree declaration, String name, int parameters) {
        return declaration.getMembers().stream()
                .anyMatch(member -> member instanceof MethodTree method
                        && method.getName().contentEquals(name)
                        && method.getParameters().size() == parameters);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
