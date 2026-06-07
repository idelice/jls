package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.*;
import org.javacs.CompilerProvider;
import org.javacs.LombokAnnotations;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class AddLombokAnnotations implements Rewrite {
    final String className;
    final Set<String> annotations;
    final int cursorPosition;

    public AddLombokAnnotations(String className, Set<String> annotations, int cursorPosition) {
        this.className = className;
        this.annotations = annotations;
        this.cursorPosition = cursorPosition;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var file = compiler.findTypeDeclaration(className);
        if (file == CompilerProvider.NOT_FOUND) {
            return CANCELLED;
        }
        try (var task = compiler.compileFast(file)) {
            var root = task.root(file);
            if (root == null) {
                return CANCELLED;
            }
            var trees = Trees.instance(task.task);
            var pos = trees.getSourcePositions();

            var classTree = findClassTree(root, className);
            if (classTree == null) {
                return CANCELLED;
            }

            Set<String> existingAnnotations = new HashSet<>();
            for (var ann : classTree.getModifiers().getAnnotations()) {
                existingAnnotations.add(LombokAnnotations.simpleName(ann.getAnnotationType().toString()));
            }

            var toAdd = new LinkedHashSet<String>();
            for (var ann : annotations) {
                if (LombokAnnotations.isLombokAnnotationType(ann) && !existingAnnotations.contains(ann)) {
                    toAdd.add(ann);
                }
            }

            if (toAdd.isEmpty()) {
                return CANCELLED;
            }

            var startClass = (int) pos.getStartPosition(root, classTree);
            var lines = root.getLineMap();
            var classLine = (int) lines.getLineNumber(startClass);
            var classColumn = (int) lines.getColumnNumber(startClass);
            var startLinePos = (int) lines.getStartPosition(classLine);
            var indent = startClass - startLinePos;

            List<TextEdit> edits = new ArrayList<>();

            var annotationText = new StringBuilder();
            for (var ann : toAdd) {
                annotationText.append(" ".repeat(indent)).append("@").append(ann).append("\n");
            }
            annotationText.append(" ".repeat(indent));

            var insertPoint = new Position(classLine - 1, classColumn - 1);
            edits.add(new TextEdit(new Range(insertPoint, insertPoint), annotationText.toString()));

            var hasWildcard = false;
            Set<String> importedSimpleNames = new HashSet<>();
            for (var imp : root.getImports()) {
                var qid = imp.getQualifiedIdentifier().toString();
                if (qid.equals("lombok.*")) {
                    hasWildcard = true;
                    break;
                }
                if (qid.startsWith("lombok.")) {
                    importedSimpleNames.add(qid.substring("lombok.".length()));
                }
            }

            if (!hasWildcard) {
                for (var ann : toAdd) {
                    if (!importedSimpleNames.contains(ann)) {
                        var importName = ann.equals("Slf4j") ? "lombok.extern.slf4j.Slf4j" : "lombok." + ann;
                        var text = "import " + importName + ";\n";
                        var point = importInsertPosition(root, pos, importName);
                        edits.add(new TextEdit(new Range(point, point), text));
                    }
                }
            }

            // Sort edits in descending order (by start position) so they apply from bottom up
            edits.sort(
                    Comparator.<TextEdit>comparingInt(e -> e.range.start.line)
                            .reversed()
                            .thenComparingInt(e -> e.range.start.character));

            return Map.of(file, edits.toArray(new TextEdit[0]));
        }
    }

    private static ClassTree findClassTree(CompilationUnitTree root, String className) {
        var simpleName = className;
        var lastDot = className.lastIndexOf('.');
        if (lastDot >= 0) {
            simpleName = className.substring(lastDot + 1);
        }
        for (var decl : root.getTypeDecls()) {
            if (decl instanceof ClassTree) {
                var ct = (ClassTree) decl;
                if (ct.getSimpleName().contentEquals(simpleName)) {
                    return ct;
                }
            }
        }
        return null;
    }

    private static Position importInsertPosition(
            CompilationUnitTree root, SourcePositions pos, String importName) {
        var imports = root.getImports();
        // Find the first import that sorts after the new one
        for (var imp : imports) {
            var qid = imp.getQualifiedIdentifier().toString();
            // Skip static imports when deciding position of regular imports
            if (imp.isStatic()) continue;
            if (importName.compareTo(qid) < 0) {
                var offset = pos.getStartPosition(root, imp);
                var line = (int) root.getLineMap().getLineNumber(offset);
                return new Position(line - 1, 0);
            }
        }
        // Insert after the last non-static import
        for (int i = imports.size() - 1; i >= 0; i--) {
            var imp = imports.get(i);
            if (!imp.isStatic()) {
                var offset = pos.getStartPosition(root, imp);
                var line = (int) root.getLineMap().getLineNumber(offset);
                return new Position(line, 0);
            }
        }
        // No non-static imports — insert after package declaration
        if (root.getPackage() != null) {
            var offset = pos.getStartPosition(root, root.getPackage());
            var line = (int) root.getLineMap().getLineNumber(offset);
            return new Position(line, 0);
        }
        // No package either — insert at top of file
        return new Position(0, 0);
    }
}
