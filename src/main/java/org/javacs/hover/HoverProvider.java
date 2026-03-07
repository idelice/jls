package org.javacs.hover;

import com.google.gson.JsonNull;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.CompletionData;
import org.javacs.FindHelper;
import org.javacs.JsonHelper;
import org.javacs.MarkdownHelper;
import org.javacs.ParseTask;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.MarkupContent;
import org.javacs.lsp.MarkupKind;

public class HoverProvider {
    final CompilerProvider compiler;

    public HoverProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public MarkupContent hover(Path file, int line, int column) {
        var annotationTypeName = annotationTypeNameAt(file, line, column);
        try (var task = compiler.compileFastWithProcessors(file)) {
            var root = task.root(file);
            if (task.roots.size() > 1) {
                LOG.fine(String.format("[perf] hover_root_select file=%s roots=%d", file, task.roots.size()));
            }
            var position = root.getLineMap().getPosition(line, column);
            Element element = null;
            if (annotationTypeName != null) {
                element = task.task.getElements().getTypeElement(annotationTypeName);
            }
            if (element == null) {
                element = findAnnotationElement(task, root, position);
            }
            if (element == null) {
                element = new FindHoverElement(task.task).scan(root, position);
            }
            if (element == null) return null;
            var docs = docs(task, element);
            var markdown = new StringBuilder();
            if (element instanceof TypeElement) {
                markdown.append(renderTypeHeader((TypeElement) element));
            } else {
                var code = printType(element);
                markdown.append("```java\n").append(code).append("\n```");
            }
            if (!docs.isEmpty()) {
                markdown.append("\n\n---\n\n").append(docs);
            }
            return new MarkupContent(MarkupKind.Markdown, markdown.toString());
        }
    }

    private Element findAnnotationElement(CompileTask task, CompilationUnitTree root, long cursor) {
        return new TreePathScanner<Element, Long>() {
            @Override
            public Element visitAnnotation(AnnotationTree annotation, Long find) {
                var positions = Trees.instance(task.task).getSourcePositions();
                var start = positions.getStartPosition(root, annotation);
                var end = positions.getEndPosition(root, annotation);
                if (start <= find && find < end) {
                    var resolved = resolveAnnotationType(task, root, annotation);
                    if (resolved != null) {
                        return resolved;
                    }
                }
                return super.visitAnnotation(annotation, find);
            }

            @Override
            public Element reduce(Element left, Element right) {
                if (left != null) {
                    return left;
                }
                return right;
            }
        }.scan(root, cursor);
    }

    private Element resolveAnnotationType(CompileTask task, CompilationUnitTree root, AnnotationTree annotation) {
        var trees = Trees.instance(task.task);
        var typePath = new TreePath(new TreePath(root), annotation.getAnnotationType());
        var direct = trees.getElement(typePath);
        if (direct != null) {
            return direct;
        }
        var elements = task.task.getElements();
        var simpleOrQualified = annotation.getAnnotationType().toString();
        if (simpleOrQualified.contains(".")) {
            return elements.getTypeElement(simpleOrQualified);
        }
        for (var imp : root.getImports()) {
            var imported = imp.getQualifiedIdentifier().toString();
            if (imported.endsWith("." + simpleOrQualified)) {
                var resolved = elements.getTypeElement(imported);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        var packageName = root.getPackageName();
        if (packageName != null) {
            var local = elements.getTypeElement(packageName + "." + simpleOrQualified);
            if (local != null) {
                return local;
            }
        }
        return elements.getTypeElement("java.lang." + simpleOrQualified);
    }

    private String annotationTypeNameAt(Path file, int line, int column) {
        var parse = compiler.parse(file);
        var root = parse.root;
        var cursor = root.getLineMap().getPosition(line, column);
        return new TreePathScanner<String, Long>() {
            @Override
            public String visitAnnotation(AnnotationTree annotation, Long find) {
                var positions = Trees.instance(parse.task).getSourcePositions();
                var start = positions.getStartPosition(root, annotation);
                var end = positions.getEndPosition(root, annotation);
                if (start <= find && find < end) {
                    return resolveAnnotationTypeName(root, annotation);
                }
                return super.visitAnnotation(annotation, find);
            }

            @Override
            public String reduce(String left, String right) {
                if (left != null) {
                    return left;
                }
                return right;
            }
        }.scan(root, cursor);
    }

    private String resolveAnnotationTypeName(CompilationUnitTree root, AnnotationTree annotation) {
        var simpleOrQualified = annotation.getAnnotationType().toString();
        if (simpleOrQualified.contains(".")) {
            return simpleOrQualified;
        }
        for (var imp : root.getImports()) {
            var imported = imp.getQualifiedIdentifier().toString();
            if (imported.endsWith("." + simpleOrQualified)) {
                return imported;
            }
            if (imported.endsWith(".*")) {
                return imported.substring(0, imported.length() - 1) + simpleOrQualified;
            }
        }
        var packageName = root.getPackageName();
        if (packageName != null) {
            return packageName + "." + simpleOrQualified;
        }
        return "java.lang." + simpleOrQualified;
    }

    public void resolveCompletionItem(CompletionItem item) {
        if (item.data == null || item.data == JsonNull.INSTANCE) return;
        var data = JsonHelper.GSON.fromJson(item.data, CompletionData.class);
        var source = compiler.findAnywhere(data.className);
        if (source.isEmpty()) return;
        var task = compiler.parse(source.get());
        Tree tree;
        try {
            tree = findItem(task, data);
        } catch (RuntimeException missingGeneratedItem) {
            LOG.fine(
                    String.format(
                            "Skip completion item resolve for unresolved member %s#%s",
                            data.className, data.memberName));
            return;
        }
        resolveDetail(item, data, tree);
        var path = Trees.instance(task.task).getPath(task.root, tree);
        if (path == null) return;
        var docTree = DocTrees.instance(task.task).getDocCommentTree(path);
        if (docTree == null) return;
        item.documentation = MarkdownHelper.asMarkupContent(docTree);
    }

    // TODO consider showing actual source code instead of just types and names
    private void resolveDetail(CompletionItem item, CompletionData data, Tree tree) {
        if (tree instanceof MethodTree) {
            var method = (MethodTree) tree;
            var parameters = new StringJoiner(", ");
            for (var p : method.getParameters()) {
                parameters.add(p.getType() + " " + p.getName());
            }
            item.detail = method.getReturnType() + " " + method.getName() + "(" + parameters + ")";
            if (!method.getThrows().isEmpty()) {
                var exceptions = new StringJoiner(", ");
                for (var e : method.getThrows()) {
                    exceptions.add(e.toString());
                }
                item.detail += " throws " + exceptions;
            }
            if (data.plusOverloads != 0) {
                item.detail += " (+" + data.plusOverloads + " overloads)";
            }
        }
    }

    private Tree findItem(ParseTask task, CompletionData data) {
        if (data.erasedParameterTypes != null) {
            return FindHelper.findMethod(task, data.className, data.memberName, data.erasedParameterTypes);
        }
        if (data.memberName != null) {
            return FindHelper.findField(task, data.className, data.memberName);
        }
        if (data.className != null) {
            return FindHelper.findType(task, data.className);
        }
        throw new RuntimeException("no className");
    }

    private String docs(CompileTask task, Element element) {
        if (element instanceof TypeElement) {
            var type = (TypeElement) element;
            var className = type.getQualifiedName().toString();
            var file = compiler.findAnywhere(className);
            if (file.isEmpty()) return "";
            var parse = compiler.parse(file.get());
            var tree = FindHelper.findType(parse, className);
            return docs(parse, tree);
        } else if (element.getKind() == ElementKind.FIELD) {
            var field = (VariableElement) element;
            var type = (TypeElement) field.getEnclosingElement();
            var className = type.getQualifiedName().toString();
            var file = compiler.findAnywhere(className);
            if (file.isEmpty()) return "";
            var parse = compiler.parse(file.get());
            try {
                var tree = FindHelper.findField(parse, className, field.getSimpleName().toString());
                return docs(parse, tree);
            } catch (RuntimeException e) {
                LOG.fine(String.format("Skip hover docs for unresolved field %s#%s", className, field.getSimpleName()));
                return "";
            }
        } else if (element instanceof ExecutableElement) {
            var method = (ExecutableElement) element;
            var type = (TypeElement) method.getEnclosingElement();
            var className = type.getQualifiedName().toString();
            var methodName = method.getSimpleName().toString();
            var erasedParameterTypes = FindHelper.erasedParameterTypes(task, method);
            var file = compiler.findAnywhere(className);
            if (file.isEmpty()) return "";
            var parse = compiler.parse(file.get());
            try {
                var tree = FindHelper.findMethod(parse, className, methodName, erasedParameterTypes);
                return docs(parse, tree);
            } catch (RuntimeException e) {
                LOG.fine(String.format("Skip hover docs for unresolved method %s#%s", className, methodName));
                return "";
            }
        } else {
            return "";
        }
    }

    private String docs(ParseTask task, Tree tree) {
        var path = Trees.instance(task.task).getPath(task.root, tree);
        var docTree = DocTrees.instance(task.task).getDocCommentTree(path);
        if (docTree == null) return "";
        return MarkdownHelper.asMarkdown(docTree);
    }

    // TODO this should be merged with logic in CompletionProvider
    // TODO this should parameterize the type
    // TODO show more information about declarations---was this a parameter, a field? What were the modifiers?
    private String printType(Element e) {
        if (e instanceof ExecutableElement) {
            var m = (ExecutableElement) e;
            return ShortTypePrinter.DEFAULT.printMethod(m);
        } else if (e instanceof VariableElement) {
            var v = (VariableElement) e;
            return ShortTypePrinter.DEFAULT.print(v.asType()) + " " + v;
        } else if (e instanceof TypeElement) {
            var t = (TypeElement) e;
            var lines = new StringJoiner("\n");
            lines.add(hoverTypeDeclaration(t) + " {");
            for (var member : t.getEnclosedElements()) {
                // TODO check accessibility
                if (member instanceof ExecutableElement || member instanceof VariableElement) {
                    lines.add("  " + printType(member) + ";");
                } else if (member instanceof TypeElement) {
                    lines.add("  " + hoverTypeDeclaration((TypeElement) member) + " { /* removed */ }");
                }
            }
            lines.add("}");
            return lines.toString();
        } else {
            return e.toString();
        }
    }

    private String hoverTypeDeclaration(TypeElement t) {
        var result = new StringBuilder();
        switch (t.getKind()) {
            case ANNOTATION_TYPE:
                result.append("@interface");
                break;
            case INTERFACE:
                result.append("interface");
                break;
            case CLASS:
                result.append("class");
                break;
            case ENUM:
                result.append("enum");
                break;
            default:
                LOG.warning("Don't know what to call type element " + t);
                result.append("_");
        }
        result.append(" ").append(ShortTypePrinter.DEFAULT.print(t.asType()));
        var superType = ShortTypePrinter.DEFAULT.print(t.getSuperclass());
        switch (superType) {
            case "Object":
            case "none":
                break;
            default:
                result.append(" extends ").append(superType);
        }
        return result.toString();
    }

    private String renderTypeHeader(TypeElement type) {
        var qualifiedName = type.getQualifiedName().toString();
        var packageName = packageName(qualifiedName);
        if (packageName.isEmpty()) {
            packageName = "(default package)";
        }
        var builder = new StringBuilder();
        builder.append("**").append(packageName).append("**\n");
        if (!qualifiedName.isEmpty()) {
            builder.append(qualifiedName).append("\n");
        }
        builder.append(typeSignature(type));
        return builder.toString();
    }

    private String typeSignature(TypeElement type) {
        var signature = new StringBuilder();
        var modifiers = typeModifiers(type.getModifiers());
        if (!modifiers.isEmpty()) {
            signature.append(modifiers).append(" ");
        }
        signature.append(typeKind(type)).append(" ").append(type.getSimpleName());

        var selfQualified = type.getQualifiedName().toString();
        var selfSimple = type.getSimpleName().toString();
        var interfaces = type.getInterfaces().stream()
                .map(t -> displayType(t.toString(), selfQualified, selfSimple))
                .toList();
        var superType = type.getSuperclass();
        var superName = displayType(superType.toString(), selfQualified, selfSimple);

        if (type.getKind() == ElementKind.INTERFACE || type.getKind() == ElementKind.ANNOTATION_TYPE) {
            if (!interfaces.isEmpty()) {
                signature.append("\n").append(formatImplements("extends", interfaces));
            }
        } else {
            if (!superName.equals("Object") && !superName.equals("none")) {
                signature.append("\n").append("extends ").append(superName);
            }
            if (!interfaces.isEmpty()) {
                signature.append("\n").append(formatImplements("implements", interfaces));
            }
        }
        return signature.toString();
    }

    private String typeKind(TypeElement type) {
        switch (type.getKind()) {
            case ANNOTATION_TYPE:
                return "@interface";
            case INTERFACE:
                return "interface";
            case CLASS:
                return "class";
            case ENUM:
                return "enum";
            default:
                return "class";
        }
    }

    private String typeModifiers(Set<Modifier> modifiers) {
        var order = List.of(
                Modifier.PUBLIC,
                Modifier.PROTECTED,
                Modifier.PRIVATE,
                Modifier.ABSTRACT,
                Modifier.STATIC,
                Modifier.FINAL,
                Modifier.SEALED,
                Modifier.NON_SEALED);
        var join = new StringJoiner(" ");
        for (var m : order) {
            if (modifiers.contains(m)) {
                join.add(m.toString());
            }
        }
        return join.toString();
    }

    private String formatImplements(String keyword, List<String> types) {
        if (types.isEmpty()) return "";
        var indent = " ".repeat(keyword.length() + 1);
        var result = new StringBuilder();
        result.append(keyword).append(" ").append(types.get(0));
        for (int i = 1; i < types.size(); i++) {
            result.append(",\n").append(indent).append(types.get(i));
        }
        return result.toString();
    }

    private String displayType(String raw, String selfQualified, String selfSimple) {
        var value = raw.replace(selfQualified, selfSimple);
        value = value.replace("java.lang.", "");
        return value;
    }

    private String packageName(String qualifiedName) {
        var lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot == -1) return "";
        return qualifiedName.substring(0, lastDot);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
