package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

public class RenameMethod implements Rewrite {
    final String className, methodName;
    final String[] erasedParameterTypes;
    final String newName;

    public RenameMethod(String className, String methodName, String[] erasedParameterTypes, String newName) {
        this.className = className;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
        this.newName = newName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return rewrite(compiler, __ -> compiler, (__, ___) -> true);
    }

    public Map<Path, TextEdit[]> rewrite(
            CompilerProvider compiler,
            Function<Path, CompilerProvider> compilerForFile,
            BiPredicate<Path, Path> candidateAllowed) {
        LOG.info("Rewrite " + className + "#" + methodName + " to " + newName + "...");
        var paths = compiler.findMemberReferences(className, methodName);
        if (paths.length == 0) {
            LOG.warning("...no references to " + className + "#" + methodName);
            return Map.of();
        }
        LOG.info("...check " + paths.length + " files for references");
        var declaration = compiler.findTypeDeclaration(className);
        var groups = new LinkedHashMap<CompilerProvider, LinkedHashSet<Path>>();
        for (var path : paths) {
            if (!candidateAllowed.test(declaration, path)) continue;
            groups.computeIfAbsent(compilerForFile.apply(path), __ -> new LinkedHashSet<>()).add(path);
        }
        var edits = new LinkedHashMap<Path, TextEdit[]>();
        for (var entry : groups.entrySet()) {
            if (declaration != CompilerProvider.NOT_FOUND) entry.getValue().add(declaration);
            try (var compile = entry.getKey().compileScan(entry.getValue().toArray(Path[]::new))) {
                edits.putAll(new RenameHelper(compile).renameMethod(
                        compile.roots, className, methodName, erasedParameterTypes, newName));
            }
        }
        return edits;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
