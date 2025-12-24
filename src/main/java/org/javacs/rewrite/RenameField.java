package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
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
        var paths = compiler.findMemberReferences(className, fieldName);
        if (paths.length == 0) {
            LOG.warning("...no references to " + className + "#" + fieldName);
            return Map.of();
        }
        LOG.info("...check " + paths.length + " files for references");
        try (var compile = compiler.compile(paths)) {
            var helper = new RenameHelper(compile);
            var edits = helper.renameField(compile.roots, className, fieldName, newName);
            return edits;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
