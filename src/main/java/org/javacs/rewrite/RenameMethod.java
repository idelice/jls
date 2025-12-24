package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
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
        LOG.info("Rewrite " + className + "#" + methodName + " to " + newName + "...");
        var paths = compiler.findMemberReferences(className, methodName);
        if (paths.length == 0) {
            LOG.warning("...no references to " + className + "#" + methodName);
            return Map.of();
        }
        LOG.info("...check " + paths.length + " files for references");
        try (var compile = compiler.compile(paths)) {
            var helper = new RenameHelper(compile);
            var edits = helper.renameMethod(compile.roots, className, methodName, erasedParameterTypes, newName);
            return edits;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
