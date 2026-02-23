package org.javacs.rewrite;

import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class AutoFixImports implements Rewrite {
    final Path file;

    public AutoFixImports(Path file) {
        this.file = file;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        LOG.info("Fix imports in " + file + "...");
        try (var task = compiler.compile(file)) {
            var used = usedImports(task);
            var unresolved = unresolvedNames(task);
            var resolved = resolveNames(compiler, task, unresolved);
            var all = new ArrayList<String>();
            all.addAll(used);
            all.addAll(resolved.values());
            all = new ArrayList<>(new LinkedHashSet<>(all));
            all.sort(String::compareTo); // TODO this is not always a good order
            var edits = new ArrayList<TextEdit>();
            edits.add(replaceImports(task, all));
            return Map.of(file, edits.toArray(new TextEdit[edits.size()]));
        }
    }

    private Set<String> usedImports(CompileTask task) {
        var used = new HashSet<String>();
        new FindUsedImports(task.task).scan(task.root(), used);
        return used;
    }

    private Set<String> unresolvedNames(CompileTask task) {
        var names = new HashSet<String>();
        for (var d : task.diagnostics) {
            if (!d.getCode().equals("compiler.err.cant.resolve.location")) continue;
            if (!d.getSource().toUri().equals(file.toUri())) continue;
            var start = (int) d.getStartPosition();
            var end = (int) d.getEndPosition();
            CharSequence contents;
            try {
                contents = d.getSource().getCharContent(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var name = contents.subSequence(start, end).toString();
            if (!name.matches("[A-Z]\\w+")) continue;
            names.add(name);
        }
        return names;
    }

    private Map<String, String> resolveNames(CompilerProvider compiler, CompileTask task, Set<String> unresolved) {
        var resolved = new HashMap<String, String>();
        var importedTypes = new LinkedHashSet<>(compiler.imports());
        var allTopLevelTypes = new LinkedHashSet<>(compiler.publicTopLevelTypes());
        var thisPackage = packageName(task);
        for (var className : unresolved) {
            var candidates = matchingCandidates(importedTypes, className, thisPackage);
            if (candidates.isEmpty()) {
                candidates = matchingCandidates(allTopLevelTypes, className, thisPackage);
            }
            if (candidates.isEmpty()) continue;
            if (candidates.size() > 1) {
                LOG.warning("..." + className + " is ambiguous between " + String.join(", ", candidates));
                continue;
            }
            var resolvedType = candidates.iterator().next();
            LOG.info("...resolve " + className + " to " + resolvedType);
            resolved.put(className, resolvedType);
        }
        return resolved;
    }

    private LinkedHashSet<String> matchingCandidates(
            Set<String> candidateTypes, String className, String thisPackage) {
        var candidates = new LinkedHashSet<String>();
        for (var i : candidateTypes) {
            if (!i.endsWith("." + className)) continue;
            if (i.startsWith("java.lang.")) continue;
            if (packageName(i).equals(thisPackage)) continue;
            candidates.add(i);
        }
        return candidates;
    }

    private String packageName(CompileTask task) {
        var root = task.root();
        if (root.getPackageName() == null) return "";
        return root.getPackageName().toString();
    }

    private String packageName(String qualifiedName) {
        var lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot <= 0) return "";
        return qualifiedName.substring(0, lastDot);
    }

    private TextEdit replaceImports(CompileTask task, List<String> qualifiedNames) {
        var pos = Trees.instance(task.task).getSourcePositions();
        var root = task.root();
        var firstNonStatic = -1;
        var lastNonStatic = -1;
        for (var i : root.getImports()) {
            if (i.isStatic()) continue;
            if (firstNonStatic == -1) {
                var start = pos.getStartPosition(root, i);
                firstNonStatic = (int) root.getLineMap().getLineNumber(start) - 1;
            }
            var end = pos.getEndPosition(root, i);
            lastNonStatic = (int) root.getLineMap().getLineNumber(end);
        }

        var text = new StringBuilder();
        for (var qn : qualifiedNames) {
            text.append("import ").append(qn).append(";\n");
        }

        if (firstNonStatic != -1) {
            return new TextEdit(new Range(new Position(firstNonStatic, 0), new Position(lastNonStatic, 0)), text.toString());
        }

        var insertLine = 0;
        if (root.getPackage() != null) {
            var end = pos.getEndPosition(root, root.getPackage());
            insertLine = (int) root.getLineMap().getLineNumber(end);
        }
        return new TextEdit(new Range(new Position(insertLine, 0), new Position(insertLine, 0)), text.toString());
    }

    private static final Logger LOG = Logger.getLogger("main");
}
