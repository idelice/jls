package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import java.util.Objects;
import javax.lang.model.element.*;

class FindUsedImports extends TreePathScanner<Void, Set<String>> {
    private final Trees trees;
    private final Set<String> imports = new HashSet<String>();

    FindUsedImports(JavacTask task) {
        this.trees = Trees.instance(task);
    }

    @Override
    public Void visitImport(ImportTree t, Set<String> references) {
        if (!t.isStatic()) {
            imports.add(Objects.toString(t.getQualifiedIdentifier(), ""));
        }
        return super.visitImport(t, references);
    }

    @Override
    public Void visitIdentifier(IdentifierTree t, Set<String> references) {
        var e = trees.getElement(getCurrentPath());
        if (e instanceof TypeElement) {
            var type = (TypeElement) e;
            var qualifiedName = type.getQualifiedName().toString();
            var packageName = packageName(qualifiedName);
            var starImport = packageName + ".*";
            if (imports.contains(qualifiedName)) {
                references.add(qualifiedName);
            } else if (imports.contains(starImport)) {
                references.add(starImport);
            }
        }
        return null;
    }

    private String packageName(String qualifiedName) {
        var lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot != -1) {
            return qualifiedName.substring(0, lastDot);
        }
        return "";
    }
}
