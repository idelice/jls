package org.javacs.provider;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Path;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import org.javacs.*;
import org.javacs.lsp.*;

public class HoverProvider {
    final CompilerProvider compiler;

    public HoverProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public MarkupContent hover(Path file, int line, int column) {
        try (var task = compiler.compileFast(file)) {
            var root = task.root(file);
            long cursor;
            try {
                cursor = FileStore.offset(root.getSourceFile().getCharContent(true).toString(), line, column);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(task).scan(root, cursor);
            if (path == null) return null;

            var element = Trees.instance(task.task).getElement(path);
            if (element == null) return null;

            var markdown = new StringBuilder();

            var typeElement = enclosingType(element);
            if (typeElement != null) {
                var qualified = typeElement.getQualifiedName().toString();
                var lastDot = qualified.lastIndexOf('.');
                if (lastDot >= 0) {
                    markdown.append("**")
                            .append(qualified.substring(0, lastDot))
                            .append("**\n");
                }
            }

            var sig = renderSignature(element);
            var doc = getDocComment(element, task);

            markdown.append("```java\n").append(sig).append("\n```");
            if (!doc.isEmpty()) {
                markdown.append("\n\n---\n\n").append(doc);
            }

            return new MarkupContent(MarkupKind.Markdown, markdown.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String renderSignature(Element element) {
        return switch (element.getKind()) {
            case METHOD, CONSTRUCTOR -> renderMethodSignature((ExecutableElement) element);
            case FIELD, ENUM_CONSTANT -> renderFieldSignature((VariableElement) element);
            case CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD -> renderClassSignature((TypeElement) element);
            case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER -> renderVariableSignature(
                    (VariableElement) element);
            default -> element.toString();
        };
    }

    private String renderMethodSignature(ExecutableElement method) {
        var sb = new StringBuilder();
        var modifiers = method.getModifiers().stream()
                .map(m -> m.toString().toLowerCase())
                .filter(m -> !m.isEmpty())
                .collect(Collectors.joining(" "));
        if (!modifiers.isEmpty()) sb.append(modifiers).append(" ");

        var typeParams = method.getTypeParameters();
        if (!typeParams.isEmpty()) {
            sb.append("<");
            var tp = new StringJoiner(", ");
            for (var t : typeParams) tp.add(t.asType().toString());
            sb.append(tp).append("> ");
        }

        sb.append(simpleTypeName(method.getReturnType())).append(" ");
        sb.append(method.getSimpleName()).append("(");
        var params = new StringJoiner(", ");
        for (var p : method.getParameters()) {
            params.add(simpleTypeName(p.asType()) + " " + p.getSimpleName());
        }
        sb.append(params).append(")");
        var thrown = method.getThrownTypes();
        if (!thrown.isEmpty()) {
            sb.append(" throws ");
            var tj = new StringJoiner(", ");
            for (var t : thrown) tj.add(t.toString());
            sb.append(tj);
        }
        return sb.toString();
    }

    private String renderFieldSignature(VariableElement field) {
        return simpleTypeName(field.asType()) + " " + field.getSimpleName();
    }

    private String renderClassSignature(TypeElement type) {
        var sb = new StringBuilder();
        sb.append(type.getQualifiedName()).append("\n");
        var modifiers = type.getModifiers().stream()
                .map(m -> m.toString().toLowerCase())
                .filter(m -> !m.isEmpty())
                .collect(Collectors.joining(" "));
        if (!modifiers.isEmpty()) sb.append(modifiers).append(" ");

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
        return simpleTypeName(variable.asType()) + " " + variable.getSimpleName();
    }

    private TypeElement enclosingType(Element element) {
        var current = element.getEnclosingElement();
        while (current != null) {
            if (current instanceof TypeElement type) return type;
            current = current.getEnclosingElement();
        }
        return null;
    }

    private String simpleTypeName(TypeMirror type) {
        return switch (type.getKind()) {
            case DECLARED -> {
                var declared = (DeclaredType) type;
                var name = declared.asElement().getSimpleName().toString();
                var args = declared.getTypeArguments();
                if (args.isEmpty()) yield name;
                var ja = new StringJoiner(", ");
                for (var a : args) ja.add(simpleTypeName(a));
                yield name + "<" + ja + ">";
            }
            case ARRAY -> {
                var array = (ArrayType) type;
                yield simpleTypeName(array.getComponentType()) + "[]";
            }
            case TYPEVAR -> type.toString();
            case WILDCARD -> "?";
            default -> type.toString();
        };
    }

    private String getDocComment(Element element, CompileTask task) {
        var elements = task.task.getElements();
        var doc = elements.getDocComment(element);
        if (doc != null && !doc.isEmpty()) return doc.trim();

        if (element instanceof ExecutableElement method) {
            return methodDocFromSource(method, task);
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
            var path = Trees.instance(task.task).getPath(parse.root(), methodTree);
            var docTree = DocTrees.instance(task.task).getDocCommentTree(path);
            return docTree != null ? MarkdownHelper.asMarkdown(docTree) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
