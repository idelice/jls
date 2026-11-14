package org.javacs.provider;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Path;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.util.logging.Logger;
import org.javacs.*;
import org.javacs.lsp.*;
import org.javacs.navigation.NavigationHelper;
import org.javacs.resolve.TypeNames;

public class HoverProvider {
    private static final Logger LOG = Logger.getLogger("main");
    final CompilerProvider compiler;

    public HoverProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public MarkupContent hover(Path file, int line, int column) {
        var start = System.currentTimeMillis();
        try (var task = compiler.compile(file)) {
            var path = NavigationHelper.findPath(task, file, line, column);
            if (path == null) return null;

            var element = task.trees.getElement(path);
            if (element == null) return null;
            if (element.asType().getKind() == TypeKind.ERROR) return null;

            var markdown = new StringBuilder();

            var typeElement = NavigationHelper.enclosingType(element.getEnclosingElement());
            if (typeElement == null && element instanceof TypeElement self) {
                typeElement = self;
            }
            if (typeElement != null) {
                var packageName = TypeNames.packageName(typeElement.getQualifiedName().toString());
                if (!packageName.isEmpty()) {
                    markdown.append("**").append(packageName).append("**\n\n");
                }
            }

            var sig = renderSignature(element, task);
            var doc = getDocComment(element, task);

            markdown.append("```java\n").append(sig).append("\n```");
            if (!doc.isEmpty()) {
                markdown.append("\n\n---\n\n").append(doc);
            }

            return new MarkupContent(MarkupKind.Markdown, markdown.toString());
        } catch (Exception e) {
            return null;
        } finally {
            LOG.info(String.format("[cache] hover:compile %dms", System.currentTimeMillis() - start));
        }
    }

    private String renderSignature(Element element, CompileTask task) {
        return switch (element.getKind()) {
            case METHOD, CONSTRUCTOR -> renderMethodSignature((ExecutableElement) element, task);
            case FIELD, ENUM_CONSTANT -> renderVariableSignature((VariableElement) element);
            case CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD -> renderClassSignature((TypeElement) element);
            case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER -> renderVariableSignature(
                    (VariableElement) element);
            default -> element.toString();
        };
    }

    private static String modifierPrefix(Element element) {
        var modifiers = element.getModifiers().stream()
                .map(m -> m.toString().toLowerCase())
                .filter(m -> !m.isEmpty())
                .collect(Collectors.joining(" "));
        return modifiers.isEmpty() ? "" : modifiers + " ";
    }

    private String renderMethodSignature(ExecutableElement method, CompileTask task) {
        var sb = new StringBuilder();
        sb.append(modifierPrefix(method));

        var typeParams = method.getTypeParameters();
        if (!typeParams.isEmpty()) {
            sb.append("<");
            var tp = new StringJoiner(", ");
            for (var t : typeParams) tp.add(t.asType().toString());
            sb.append(tp).append("> ");
        }

        sb.append(ElementSignatureHelper.simpleTypeName(method.getReturnType())).append(" ");
        sb.append(method.getSimpleName());

        byte[] bytes = null;
        if (ElementSignatureHelper.hasSyntheticNames(method.getParameters())) {
            var owner = ElementSignatureHelper.ownerQualifiedName(method);
            if (owner != null) bytes = compiler.findClassFile(owner).orElse(null);
        }
        var names = ElementSignatureHelper.parameterNames(task, method, bytes);
        sb.append(ElementSignatureHelper.methodParams(method, names));
        var thrown = method.getThrownTypes();
        if (!thrown.isEmpty()) {
            sb.append(" throws ");
            var tj = new StringJoiner(", ");
            for (var t : thrown) tj.add(t.toString());
            sb.append(tj);
        }
        return sb.toString();
    }

    private String renderClassSignature(TypeElement type) {
        var sb = new StringBuilder();
        sb.append(modifierPrefix(type));

        var kind = switch (type.getKind()) {
            case ANNOTATION_TYPE -> "@interface";
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            default -> "class";
        };
        sb.append(kind).append(" ").append(type.getSimpleName());
        return sb.toString();
    }

    private String renderVariableSignature(VariableElement variable) {
        return ElementSignatureHelper.simpleTypeName(variable.asType()) + " " + variable.getSimpleName();
    }

    private String getDocComment(Element element, CompileTask task) {
        var elements = task.elements;
        var doc = elements.getDocComment(element);
        if (doc != null && !doc.isEmpty()) return MarkdownHelper.asMarkdown(doc.trim());

        if (element instanceof ExecutableElement method) {
            return methodDocFromSource(method, task);
        }
        if (element instanceof TypeElement type) {
            return typeDocFromSource(type, task);
        }
        return "";
    }

    private String methodDocFromSource(ExecutableElement method, CompileTask task) {
        var enclosing = method.getEnclosingElement();
        if (!(enclosing instanceof TypeElement type)) return "";
        var className = type.getQualifiedName().toString();
        var sourceFile = compiler.findAnywhere(className);
        if (sourceFile.isEmpty()) return "";

        try {
            var parse = compiler.parse(sourceFile.get());
            var erasedTypes = FindHelper.erasedParameterTypes(task, method);
            var methodTree = FindHelper.findMethod(
                    parse, className, method.getSimpleName().toString(), erasedTypes);
            return docComment(parse, methodTree);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String typeDocFromSource(TypeElement type, CompileTask task) {
        var className = type.getQualifiedName().toString();
        var sourceFile = compiler.findAnywhere(className);
        if (sourceFile.isEmpty()) return "";

        try {
            var parse = compiler.parse(sourceFile.get());
            var typeTree = FindHelper.findType(parse, className);
            if (typeTree == null) return "";
            return docComment(parse, typeTree);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String docComment(ParseTask parse, Tree tree) {
        var path = Trees.instance(parse.task()).getPath(parse.root(), tree);
        if (path == null) return "";
        var docTree = DocTrees.instance(parse.task()).getDocCommentTree(path);
        return docTree != null ? MarkdownHelper.asMarkdown(docTree) : "";
    }
}
