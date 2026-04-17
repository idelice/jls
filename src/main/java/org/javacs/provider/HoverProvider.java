package org.javacs.provider;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindNameAt;
import org.javacs.MarkdownHelper;
import org.javacs.ParseTask;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.Location;
import org.javacs.lsp.MarkupContent;
import org.javacs.lsp.MarkupKind;

public class HoverProvider {
    private record ResolvedDeclaration(ParseTask parse, TreePath path) {}

    final CompilerProvider compiler;
    final TypeIndexRouter typeIndexRouter;

    public HoverProvider(CompilerProvider compiler) {
        this(compiler, TypeIndexRouter.EMPTY);
    }

    public HoverProvider(CompilerProvider compiler, TypeIndexRouter typeIndexRouter) {
        this.compiler = compiler;
        this.typeIndexRouter = typeIndexRouter == null ? TypeIndexRouter.EMPTY : typeIndexRouter;
    }

    public MarkupContent hover(Path file, int line, int column) {
        var parse = compiler.parse(file);
        long cursor;
        try {
            cursor = FileStore.offset(parse.root().getSourceFile().getCharContent(true).toString(), line, column);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        var path = new FindNameAt(parse).scan(parse.root(), cursor);
        if (path == null) {
            return null;
        }

        var symbol = new DefinitionProvider(compiler, typeIndexRouter, file, line, column).resolveSymbol();
        var fallback = renderFromPath(parse, path);
        if (symbol.locations().isEmpty()) {
            var generated = renderGeneratedMember(symbol);
            if (generated != null) {
                return generated;
            }
            return fallback;
        }

        var resolved = resolveLocation(symbol.locations().getFirst());
        if (resolved.isEmpty()) {
            var generated = renderGeneratedMember(symbol);
            if (generated != null) {
                return generated;
            }
            return fallback;
        }

        return renderResolvedDeclaration(resolved.get(), symbol).orElseGet(() -> {
            var generated = renderGeneratedMember(symbol);
            if (generated != null) {
                return generated;
            }
            return fallback;
        });
    }

    private Optional<ResolvedDeclaration> resolveLocation(Location location) {
        if (location == null || location.uri == null || !"file".equals(location.uri.getScheme())) {
            return Optional.empty();
        }
        try {
            var target = Paths.get(location.uri);
            var parse = compiler.parse(target);
            var line = location.range.start.line + 1;
            var column = location.range.start.character + 1;
            long cursor = FileStore.offset(parse.root().getSourceFile().getCharContent(true).toString(), line, column);
            var path = new FindNameAt(parse).scan(parse.root(), cursor);
            if (path == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedDeclaration(parse, path));
        } catch (RuntimeException | java.io.IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<MarkupContent> renderResolvedDeclaration(
            ResolvedDeclaration resolved, DefinitionProvider.ResolvedSymbol symbol) {
        var tree = resolved.path().getLeaf();
        if (tree instanceof ClassTree && symbol != null && symbol.method() && symbol.memberName() != null) {
            var methodPath = findMethodInClass(resolved.parse(), resolved.path(), symbol.memberName());
            if (methodPath.isPresent()) {
                tree = methodPath.get().getLeaf();
                resolved = new ResolvedDeclaration(resolved.parse(), methodPath.get());
            } else {
                var generated = renderGeneratedMember(symbol);
                if (generated != null) {
                    return Optional.of(generated);
                }
            }
        }
        var code = renderDeclaration(resolved.parse(), resolved.path(), symbol);
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        var markdown = new StringBuilder();
        if (tree instanceof ClassTree cls) {
            markdown.append(renderTypeHeader(resolved.parse(), resolved.path(), cls));
        } else {
            markdown.append("```java\n").append(code).append("\n```");
        }
        var docs = docs(resolved.parse(), resolved.path());
        if (!docs.isEmpty()) {
            markdown.append("\n\n---\n\n").append(docs);
        }
        return Optional.of(new MarkupContent(MarkupKind.Markdown, markdown.toString()));
    }

    private MarkupContent renderFromPath(ParseTask parse, TreePath path) {
        var tree = path.getLeaf();
        var code = renderDeclaration(parse, path, null);
        if (code == null || code.isBlank()) {
            return null;
        }
        var markdown = new StringBuilder();
        if (tree instanceof ClassTree cls) {
            markdown.append(renderTypeHeader(parse, path, cls));
        } else {
            markdown.append("```java\n").append(code).append("\n```");
        }
        var docs = docs(parse, path);
        if (!docs.isEmpty()) {
            markdown.append("\n\n---\n\n").append(docs);
        }
        return new MarkupContent(MarkupKind.Markdown, markdown.toString());
    }

    private MarkupContent renderGeneratedMember(DefinitionProvider.ResolvedSymbol symbol) {
        if (symbol == null) {
            return null;
        }
        var detail = "";
        if (symbol.indexMember() != null) {
            var member = symbol.indexMember();
            detail = member.detail;
            if (detail == null || detail.isBlank()) {
                detail = member.name + "()";
            }
        } else if (symbol.method() && symbol.memberName() != null && !symbol.memberName().isBlank()) {
            detail = symbol.memberName() + "()";
        }
        if (detail.isBlank()) {
            return null;
        }
        var markdown = new StringBuilder();
        markdown.append("```java\n").append(detail).append("\n```");
        if (symbol.qualifiedType() != null && !symbol.qualifiedType().isBlank()) {
            markdown.append("\n\n").append(symbol.qualifiedType());
        }
        return new MarkupContent(MarkupKind.Markdown, markdown.toString());
    }

    private String renderDeclaration(ParseTask parse, TreePath path, DefinitionProvider.ResolvedSymbol symbol) {
        var tree = path.getLeaf();
        if (tree instanceof ClassTree cls) {
            return classSignature(cls);
        }
        if (tree instanceof MethodTree method) {
            return methodSignature(parse, method);
        }
        if (tree instanceof VariableTree variable) {
            var type = variable.getType() == null ? "var" : variable.getType().toString();
            return type + " " + variable.getName();
        }
        if (tree instanceof AnnotationTree annotation) {
            return annotation.getAnnotationType().toString();
        }
        if (symbol != null && symbol.indexMember() != null && symbol.method()) {
            return symbol.indexMember().detail;
        }
        return tree.toString();
    }

    private String classSignature(ClassTree cls) {
        var modifiers = joinModifiers(cls.getModifiers().getFlags().stream().map(Enum::toString).toList());
        var kind = switch (cls.getKind()) {
            case ANNOTATION_TYPE -> "@interface";
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            default -> "class";
        };
        var header = new StringBuilder();
        if (!modifiers.isBlank()) {
            header.append(modifiers).append(" ");
        }
        header.append(kind).append(" ").append(cls.getSimpleName());
        if (cls.getExtendsClause() != null) {
            header.append(" extends ").append(cls.getExtendsClause());
        }
        if (!cls.getImplementsClause().isEmpty()) {
            var join = new StringJoiner(", ");
            for (var implemented : cls.getImplementsClause()) {
                join.add(implemented.toString());
            }
            header.append(" implements ").append(join);
        }
        return header.toString();
    }

    private String methodSignature(ParseTask parse, MethodTree method) {
        var header = new StringBuilder();
        var modifiers = joinModifiers(method.getModifiers().getFlags().stream().map(Enum::toString).toList());
        if (!modifiers.isBlank()) {
            header.append(modifiers).append(" ");
        }
        if (method.getReturnType() != null) {
            header.append(method.getReturnType()).append(" ");
        }
        header.append(method.getName()).append("(");
        var parameters = new StringJoiner(", ");
        for (var parameter : method.getParameters()) {
            var type = parameter.getType() == null ? "var" : parameter.getType().toString();
            parameters.add(type + " " + parameter.getName());
        }
        header.append(parameters).append(")");
        if (!method.getThrows().isEmpty()) {
            var thrown = new StringJoiner(", ");
            for (var thrownType : method.getThrows()) {
                var resolved = resolveTypeName(parse, thrownType.toString());
                thrown.add(resolved.orElse(thrownType.toString()));
            }
            header.append(" throws ").append(thrown);
        }
        return header.toString();
    }

    private String renderTypeHeader(ParseTask parse, TreePath classPath, ClassTree cls) {
        var qualifiedName = qualifiedClassName(parse, classPath);
        var packageName = packageName(qualifiedName);
        if (packageName.isBlank()) {
            packageName = "(default package)";
        }
        return "**" + packageName + "**\n" + qualifiedName + "\n" + classSignature(cls);
    }

    private String qualifiedClassName(ParseTask parse, TreePath classPath) {
        var names = new ArrayList<String>();
        for (var cursor = classPath; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree cls) {
                names.add(cls.getSimpleName().toString());
            }
        }
        java.util.Collections.reverse(names);
        var pkg = parse.root().getPackageName() == null ? "" : parse.root().getPackageName().toString();
        if (pkg.isBlank()) {
            return String.join(".", names);
        }
        return pkg + "." + String.join(".", names);
    }

    private String packageName(String qualifiedName) {
        var i = qualifiedName.lastIndexOf('.');
        if (i < 0) {
            return "";
        }
        return qualifiedName.substring(0, i);
    }

    private String docs(ParseTask task, TreePath path) {
        var docTree = DocTrees.instance(task.task()).getDocCommentTree(path);
        if (docTree == null) return "";
        return MarkdownHelper.asMarkdown(docTree);
    }

    private Optional<TreePath> findMethodInClass(ParseTask parse, TreePath classPath, String methodName) {
        var classTree = (ClassTree) classPath.getLeaf();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof MethodTree method)) continue;
            if (!method.getName().contentEquals(methodName)) continue;
            return Optional.of(new TreePath(classPath, method));
        }
        return Optional.empty();
    }

    private Optional<String> resolveTypeName(ParseTask parse, String typeName) {
        return typeIndexRouter.resolveTypeName(typeName, parse.root());
    }

    private String joinModifiers(List<String> modifiers) {
        var join = new StringJoiner(" ");
        for (var modifier : modifiers) {
            join.add(modifier);
        }
        return join.toString();
    }

}
