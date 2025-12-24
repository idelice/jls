package org.javacs.rewrite;

import com.sun.source.tree.MethodTree;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Logger;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

public class ImplementAbstractMethods implements Rewrite {
    final String className;

    public ImplementAbstractMethods(String className) {
        this.className = className;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        var file = compiler.findTypeDeclaration(className);
        var insertText = new StringJoiner("\n");
        try (var task = compiler.compile(file)) {
            var elements = task.task.getElements();
            var types = task.task.getTypes();
            var trees = Trees.instance(task.task);
            var thisClass = elements.getTypeElement(className);
            var thisType = (DeclaredType) thisClass.asType();
            var thisTree = trees.getTree(thisClass);
            var indent = EditHelper.indent(task.task, task.root(), thisTree) + 4;
            for (var member : elements.getAllMembers(thisClass)) {
                if (member.getKind() == ElementKind.METHOD && member.getModifiers().contains(Modifier.ABSTRACT)) {
                    var method = (ExecutableElement) member;
                    var source = findSource(compiler, task, method);
                    if (source == null) {
                        LOG.warning("...couldn't find source for " + method);
                    }
                    var parameterizedType = (ExecutableType) types.asMemberOf(thisType, method);
                    var text = EditHelper.printMethod(method, parameterizedType, source);
                    text = text.replaceAll("\n", "\n" + " ".repeat(indent));
                    insertText.add(text);
                }
            }
            var insert = EditHelper.insertAtEndOfClass(task.task, task.root(), thisTree);
            TextEdit[] edits = {new TextEdit(new Range(insert, insert), insertText + "\n")};
            return Map.of(file, edits);
        }
    }

    private MethodTree findSource(CompilerProvider compiler, CompileTask task, ExecutableElement method) {
        var superClass = (TypeElement) method.getEnclosingElement();
        var superClassName = superClass.getQualifiedName().toString();
        var methodName = method.getSimpleName().toString();
        var erasedParameterTypes = FindHelper.erasedParameterTypes(task, method);
        var sourceFile = compiler.findAnywhere(superClassName);
        if (sourceFile.isEmpty()) return null;
        var parse = compiler.parse(sourceFile.get());
        return FindHelper.findMethod(parse, superClassName, methodName, erasedParameterTypes);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
