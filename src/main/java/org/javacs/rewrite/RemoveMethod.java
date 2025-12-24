package org.javacs.rewrite;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.TextEdit;

public class RemoveMethod implements Rewrite {
    final String className, methodName;
    final String[] erasedParameterTypes;

    public RemoveMethod(String className, String methodName, String[] erasedParameterTypes) {
        this.className = className;
        this.methodName = methodName;
        this.erasedParameterTypes = erasedParameterTypes;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var file = compiler.findTypeDeclaration(className);
        if (file == CompilerProvider.NOT_FOUND) {
            return CANCELLED;
        }
        try (var task = compiler.compile(file)) {
            var methodElement = FindHelper.findMethod(task, className, methodName, erasedParameterTypes);
            var methodTree = Trees.instance(task.task).getTree(methodElement);
            TextEdit[] edits = {new EditHelper(task.task).removeTree(task.root(), methodTree)};
            return Map.of(file, edits);
        }
    }
}
