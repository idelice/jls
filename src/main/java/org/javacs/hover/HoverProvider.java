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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
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
    final org.javacs.LombokMetadataCache lombokCache;

    public HoverProvider(CompilerProvider compiler, org.javacs.LombokMetadataCache lombokCache) {
        this.compiler = compiler;
        this.lombokCache = lombokCache;
    }

    public MarkupContent hover(Path file, int line, int column) {
        var startedNanos = System.nanoTime();
        try (var task = compiler.compile(file)) {
            var position = task.root().getLineMap().getPosition(line, column);
            var element = new FindHoverElement(task.task).scan(task.root(), position);
            LOG.fine("...HoverProvider element: " + element);
            if (element == null) return null;
            LOG.fine("...element kind: " + element.getKind());
            LOG.fine("...element class: " + element.getClass().getName());

            // Handle case where javac returns CLASS for Lombok methods
            if (element.getKind() == ElementKind.CLASS) {
                var elementName = element.toString();
                LOG.fine("...element name: " + elementName);
                // Check if this looks like a Lombok method (e.g., "com.example.Foo.getBar")
                if (elementName.contains(".")) {
                    var lastDot = elementName.lastIndexOf('.');
                    var className = elementName.substring(0, lastDot);
                    var methodName = elementName.substring(lastDot + 1);
                    LOG.fine("...possible Lombok method: class=" + className + ", method=" + methodName);

                    var resolvedChainType = resolveMethodChainReturnType(task, elementName);
                    if (resolvedChainType != null) {
                        var signature = "public " + renderSimpleType(resolvedChainType) + " " + methodName + "()";
                        LOG.fine("...resolved method-chain signature: " + signature);
                        return new MarkupContent(MarkupKind.Markdown, "```java\n" + signature + "\n```");
                    }

                    // Try to get Lombok metadata for this class
                    var metadata = org.javacs.LombokHandler.metadataForClass(task, className, this.lombokCache);
                    if (metadata != null) {
                        LOG.fine("...found Lombok metadata");
                        // Check if it's a generated getter
                        if (metadata.isGeneratedGetter(methodName)) {
                            LOG.fine("...it's a generated getter");
                            var field = metadata.fieldForGetter(methodName);
                            if (field != null) {
                                var fieldType = field.getType().toString();
                                var signature = "public " + fieldType + " " + methodName + "()";
                                LOG.fine("...generated signature: " + signature);
                                return new MarkupContent(MarkupKind.Markdown, "```java\n" + signature + "\n```");
                            }
                        }
                        // Check if it's a generated setter
                        else if (metadata.isGeneratedSetter(methodName)) {
                            LOG.fine("...it's a generated setter");
                            var field = metadata.fieldForSetter(methodName);
                            if (field != null) {
                                var fieldType = field.getType().toString();
                                var fieldName = field.getName().toString();
                                var signature = "public void " + methodName + "(" + fieldType + " " + fieldName + ")";
                                LOG.fine("...generated signature: " + signature);
                                return new MarkupContent(MarkupKind.Markdown, "```java\n" + signature + "\n```");
                            }
                        }
                    }
                }
            }

            if (element instanceof ExecutableElement) {
                var exec = (ExecutableElement) element;
                LOG.fine("...method name: " + exec.getSimpleName());
                LOG.fine("...return type: " + exec.getReturnType());
                LOG.fine("...enclosing element: " + exec.getEnclosingElement());
            }
            var docs = docs(task, element);
            var markdown = new StringBuilder();
            if (element instanceof TypeElement) {
                markdown.append(renderTypeHeader((TypeElement) element));
            } else {
                var code = printType(element);
                LOG.fine("...printType result: " + code);
                markdown.append("```java\n").append(code).append("\n```");
            }
            if (!docs.isEmpty()) {
                markdown.append("\n\n---\n\n").append(docs);
            }
            LOG.info(
                    "[perf][hover] file="
                            + file.getFileName()
                            + " line="
                            + line
                            + " col="
                            + column
                            + " elapsed_ms="
                            + ((System.nanoTime() - startedNanos) / 1_000_000));
            return new MarkupContent(MarkupKind.Markdown, markdown.toString());
        }
    }

    public void resolveCompletionItem(CompletionItem item) {
        var startedNanos = System.nanoTime();
        if (item.data == null || item.data == JsonNull.INSTANCE) return;
        var data = JsonHelper.GSON.fromJson(item.data, CompletionData.class);
        var source = compiler.findAnywhere(data.className);
        if (source.isEmpty()) return;
        var task = compiler.parse(source.get());
        var tree = findItem(task, data);
        if (tree == null) {
            LOG.fine(
                    () ->
                            "Skipping completion item resolve for missing source member: "
                                    + data.className
                                    + "."
                                    + data.memberName);
            return;
        }
        resolveDetail(item, data, tree);
        var path = Trees.instance(task.task).getPath(task.root, tree);
        var docTree = DocTrees.instance(task.task).getDocCommentTree(path);
        if (docTree == null) return;
        item.documentation = MarkdownHelper.asMarkupContent(docTree);
        LOG.info(
                "[perf][completion-resolve] class="
                        + data.className
                        + " member="
                        + data.memberName
                        + " elapsed_ms="
                        + ((System.nanoTime() - startedNanos) / 1_000_000));
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
            return FindHelper.findMethodOrNull(task, data.className, data.memberName, data.erasedParameterTypes);
        }
        if (data.memberName != null) {
            try {
                return FindHelper.findField(task, data.className, data.memberName);
            } catch (RuntimeException e) {
                return null;
            }
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
            var tree = FindHelper.findField(parse, className, field.getSimpleName().toString());
            return docs(parse, tree);
        } else if (element instanceof ExecutableElement) {
            var method = (ExecutableElement) element;
            var type = (TypeElement) method.getEnclosingElement();
            var className = type.getQualifiedName().toString();
            var methodName = method.getSimpleName().toString();
            var erasedParameterTypes = FindHelper.erasedParameterTypes(task, method);
            var file = compiler.findAnywhere(className);
            if (file.isEmpty()) return "";
            var parse = compiler.parse(file.get());
            var tree = FindHelper.findMethodOrNull(parse, className, methodName, erasedParameterTypes);
            if (tree == null) return "";
            return docs(parse, tree);
        } else {
            return "";
        }
    }

    private TypeMirror resolveMethodChainReturnType(CompileTask task, String chain) {
        var lastDot = chain.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        var ownerExpr = chain.substring(0, lastDot);
        var methodName = chain.substring(lastDot + 1);
        var ownerType = resolveTypeOrMethodChain(task, ownerExpr);
        if (!(ownerType instanceof DeclaredType)) {
            return null;
        }
        var ownerElement = ((DeclaredType) ownerType).asElement();
        if (!(ownerElement instanceof TypeElement)) {
            return null;
        }
        var ownerClass = (TypeElement) ownerElement;

        var metadata =
                org.javacs.LombokHandler.metadataForClass(
                        task, ownerClass.getQualifiedName().toString(), this.lombokCache);
        if (metadata != null) {
            String fieldName = null;
            if (methodName.startsWith("get") && methodName.length() > 3) {
                fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                fieldName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            }
            if (fieldName != null && metadata.isGeneratedGetter(methodName)) {
                for (var member : task.task.getElements().getAllMembers(ownerClass)) {
                    if (member.getKind() == ElementKind.FIELD && member.getSimpleName().contentEquals(fieldName)) {
                        return ((VariableElement) member).asType();
                    }
                }
            }
        }

        for (var member : task.task.getElements().getAllMembers(ownerClass)) {
            if (member.getKind() != ElementKind.METHOD) continue;
            if (!member.getSimpleName().contentEquals(methodName)) continue;
            var method = (ExecutableElement) member;
            if (!method.getParameters().isEmpty()) continue;
            return method.getReturnType();
        }
        return null;
    }

    private TypeMirror resolveTypeOrMethodChain(CompileTask task, String value) {
        var direct = task.task.getElements().getTypeElement(value);
        if (direct != null) {
            return direct.asType();
        }
        if (!looksLikeMethodChain(value)) {
            return null;
        }
        return resolveMethodChainReturnType(task, value);
    }

    private boolean looksLikeMethodChain(String value) {
        var lastDot = value.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= value.length() - 1) return false;
        var segment = value.substring(lastDot + 1);
        return Character.isLowerCase(segment.charAt(0));
    }

    private String docs(ParseTask task, Tree tree) {
        var path = Trees.instance(task.task).getPath(task.root, tree);
        var docTree = DocTrees.instance(task.task).getDocCommentTree(path);
        if (docTree == null) return "";
        return MarkdownHelper.asMarkdown(docTree);
    }

    private String renderSimpleType(TypeMirror type) {
        var raw = type.toString();
        var withoutPackages = raw.replaceAll("(?:(?:[a-z_][\\w$]*\\.)+)([A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*)", "$1");
        var withoutOuterClasses = withoutPackages.replaceAll("([A-Z][\\w$]*)\\.", "");
        return withoutOuterClasses.replaceAll(",\\s*", ", ");
    }

    // TODO this should be merged with logic in CompletionProvider
    // TODO this should parameterize the type
    // TODO show more information about declarations---was this a parameter, a field? What were the modifiers?
    private String printType(Element e) {
        LOG.fine("...printType called for element: " + e);
        LOG.fine("...printType element kind: " + e.getKind());
        if (e instanceof ExecutableElement) {
            var m = (ExecutableElement) e;
            LOG.fine("...printType using ShortTypePrinter.printMethod");
            var result = ShortTypePrinter.DEFAULT.printMethod(m);
            LOG.fine("...printMethod result: " + result);
            return result;
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
