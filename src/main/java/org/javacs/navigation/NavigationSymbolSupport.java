package org.javacs.navigation;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindNameAt;
import org.javacs.ParseTask;
import org.javacs.completion.TypeIndexRouter;
import org.javacs.index.IndexedMember;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.resolve.ParseTypeResolver;
import org.javacs.resolve.TypeNames;

/**
 * Shared parse/signature/key helpers for navigation providers.
 *
 * <p>This class intentionally keeps only logic that is genuinely shared between reference and
 * definition: parse-based parameter extraction, canonical/logical navigation keys, and accessor
 * aliases. Plain type-name formatting belongs to {@link TypeNames} and is called directly at
 * usage sites.
 */
final class NavigationSymbolSupport {
    private NavigationSymbolSupport() {}

    static List<String> targetParameterTypes(
            CompilerProvider compiler,
            TypeIndexRouter completionIndex,
            DefinitionProvider.ResolvedSymbol target) {
        if (target.locations().isEmpty()) {
            return List.of();
        }
        var location = target.locations().get(0);
        if (location.uri == null || !"file".equals(location.uri.getScheme())) {
            return List.of();
        }
        var targetFile = Path.of(location.uri);
        var parse = compiler.parse(targetFile);
        var line = location.range.start.line + 1;
        var column = location.range.start.character + 1;
        long cursor;
        try {
            cursor = FileStore.offset(parse.root().getSourceFile().getCharContent(true).toString(), line, column);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var path = new FindNameAt(parse).scan(parse.root(), cursor);
        if (path == null || !(path.getLeaf() instanceof MethodTree method)) {
            return List.of();
        }
        return declaredParameterTypes(parse, path, method, completionIndex, compiler);
    }

    static List<String> occurrenceParameterTypes(
            ParseTask parse,
            TreePath path,
            TypeIndexRouter completionIndex,
            CompilerProvider compiler) {
        var leaf = path.getLeaf();
        if (leaf instanceof NewClassTree newClassTree) {
            return argumentTypes(parse, path, newClassTree.getArguments(), completionIndex, compiler);
        }
        var parent = path.getParentPath() != null ? path.getParentPath().getLeaf() : null;
        if (parent instanceof NewClassTree newClassTree) {
            return argumentTypes(parse, path.getParentPath(), newClassTree.getArguments(), completionIndex, compiler);
        }
        if (parent instanceof MethodInvocationTree invocation) {
            return argumentTypes(parse, path.getParentPath(), invocation.getArguments(), completionIndex, compiler);
        }
        return List.of();
    }

    static List<String> declaredParameterTypes(
            ParseTask parse,
            TreePath path,
            MethodTree method,
            TypeIndexRouter completionIndex,
            CompilerProvider compiler) {
        var resolver = resolver(parse, path, completionIndex, compiler);
        var result = new ArrayList<String>(method.getParameters().size());
        for (VariableTree parameter : method.getParameters()) {
            if (parameter.getType() == null) {
                return List.of();
            }
            var resolved = resolver.resolveTypeTree(parameter.getType(), false);
            result.add(
                    TypeNames.canonicalBoxed(
                            resolved.map(ParseTypeResolver.TypeResolution::qualifiedType)
                                    .orElse(parameter.getType().toString())));
        }
        return result;
    }

    static List<String> argumentTypes(List<? extends ExpressionTree> arguments, ParseTypeResolver resolver) {
        var result = new ArrayList<String>(arguments.size());
        for (var argument : arguments) {
            var resolved = resolver.resolveExpression(argument);
            result.add(
                    TypeNames.canonicalBoxed(
                            resolved.map(ParseTypeResolver.TypeResolution::qualifiedType).orElse("")));
        }
        return result;
    }

    static String methodCanonicalKey(
            DefinitionProvider.ResolvedSymbol resolved,
            ParseTask parse,
            TreePath path,
            TypeIndexRouter typeIndexRouter,
            CompilerProvider compiler) {
        if (resolved.indexMember() != null && resolved.indexMember().canonicalKey != null) {
            return resolved.indexMember().canonicalKey;
        }
        if (!resolved.method() || resolved.qualifiedType() == null || resolved.memberName() == null) {
            return null;
        }
        List<String> parameterTypes;
        if (path.getLeaf() instanceof MethodTree method) {
            parameterTypes = declaredParameterTypes(parse, path, method, typeIndexRouter, compiler);
        } else if (path.getParentPath() != null && path.getParentPath().getLeaf() instanceof MethodTree method) {
            parameterTypes = declaredParameterTypes(parse, path.getParentPath(), method, typeIndexRouter, compiler);
        } else {
            parameterTypes = occurrenceParameterTypes(parse, path, typeIndexRouter, compiler);
        }
        return IndexedMember.canonicalKey(
                resolved.qualifiedType(),
                CompletionItemKind.Method,
                resolved.memberName(),
                parameterTypes.toArray(String[]::new));
    }

    static Optional<String> fieldLogicalKey(DefinitionProvider.ResolvedSymbol symbol) {
        if (symbol == null || symbol.qualifiedType() == null || symbol.memberName() == null) {
            return Optional.empty();
        }
        if (symbol.indexMember() != null && symbol.indexMember().logicalKey != null) {
            if (!symbol.method() || !symbol.indexMember().logicalKey.equals(symbol.indexMember().canonicalKey)) {
                return Optional.of(symbol.indexMember().logicalKey);
            }
        }
        if (!symbol.method()) {
            return Optional.of(
                    IndexedMember.canonicalKey(
                            symbol.qualifiedType(), CompletionItemKind.Field, symbol.memberName(), null));
        }
        return Optional.empty();
    }

    static String logicalKey(DefinitionProvider.ResolvedSymbol symbol) {
        if (symbol.indexMember() != null && symbol.indexMember().logicalKey != null) {
            return symbol.indexMember().logicalKey;
        }
        if (!symbol.method() && symbol.qualifiedType() != null && symbol.memberName() != null) {
            return IndexedMember.canonicalKey(
                    symbol.qualifiedType(), CompletionItemKind.Field, symbol.memberName(), null);
        }
        return null;
    }

    static Set<String> accessorNames(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return Set.of();
        }
        var base = fieldName.substring(0, 1).toUpperCase(Locale.ROOT) + fieldName.substring(1);
        var names = new LinkedHashSet<String>();
        names.add("get" + base);
        names.add("is" + base);
        names.add("set" + base);
        return names;
    }

    private static List<String> argumentTypes(
            ParseTask parse,
            TreePath path,
            List<? extends ExpressionTree> arguments,
            TypeIndexRouter completionIndex,
            CompilerProvider compiler) {
        var resolver = resolver(parse, path, completionIndex, compiler);
        var result = new ArrayList<String>(arguments.size());
        for (var argument : arguments) {
            var resolved = resolver.resolveExpression(argument);
            result.add(
                    TypeNames.canonicalBoxed(
                            resolved.map(ParseTypeResolver.TypeResolution::qualifiedType).orElse("")));
        }
        return result;
    }

    private static ParseTypeResolver resolver(
            ParseTask parse,
            TreePath path,
            TypeIndexRouter completionIndex,
            CompilerProvider compiler) {
        var cursor = Trees.instance(parse.task()).getSourcePositions().getStartPosition(parse.root(), path.getLeaf()) + 1;
        return new ParseTypeResolver(parse, compiler, completionIndex, cursor);
    }
}
