package org.javacs.navigation;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.*;

import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.ParseTask;
import org.javacs.completion.CompositeTypeIndex;
import org.javacs.completion.TypeMemberIndex;
import org.javacs.lsp.Location;

public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final CompositeTypeIndex completionIndex;
    private final Path file;
    private final int line, column;
    private final boolean includeDeclaration;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public ReferenceProvider(CompilerProvider compiler, Path file, int line, int column) {
        this(compiler, CompositeTypeIndex.EMPTY, file, line, column, false);
    }

    public ReferenceProvider(CompilerProvider compiler, CompositeTypeIndex completionIndex, Path file, int line, int column) {
        this(compiler, completionIndex, file, line, column, false);
    }

    public ReferenceProvider(
            CompilerProvider compiler,
            CompositeTypeIndex completionIndex,
            Path file,
            int line,
            int column,
            boolean includeDeclaration) {
        this.compiler = compiler;
        this.completionIndex = completionIndex == null ? CompositeTypeIndex.EMPTY : completionIndex;
        this.file = file;
        this.line = line;
        this.column = column;
        this.includeDeclaration = includeDeclaration;
    }

    public List<Location> find() {
        var definitions = new DefinitionProvider(compiler, completionIndex, file, line, column);
        var target = definitions.resolveSymbol();
        if (!isSupported(target)) {
            return NOT_SUPPORTED;
        }
        if (target.qualifiedType() == null) {
            return findLocalReferences(definitions, target);
        }
        if (target.memberName() == null) {
            return findTypeReferences(definitions, target);
        }
        if (fieldLogicalKey(target).isPresent()) {
            return findFieldReferencesScoped(definitions, target, fieldLogicalKey(target).get());
        }
        return findMemberReferences(definitions, target, targetParameterTypes(target));
    }

    private boolean isSupported(DefinitionProvider.ResolvedSymbol target) {
        return target != null && (!target.locations().isEmpty() || target.qualifiedType() != null || target.memberName() != null);
    }

    private List<Location> findTypeReferences(
            DefinitionProvider definitions, DefinitionProvider.ResolvedSymbol target) {
        var files = includeDeclarationFile(target.qualifiedType(), compiler.findTypeReferences(target.qualifiedType()));
        return scan(
                files,
                Set.of(target.simpleName()),
                target,
                (parse, path) -> matchesTypeReference(definitions, parse, path, target));
    }

    private List<Location> findMemberReferences(
            DefinitionProvider definitions, DefinitionProvider.ResolvedSymbol target, List<String> targetParameterTypes) {
        var files = compiler.findMemberReferences(target.qualifiedType(), target.memberName());
        var names = new java.util.LinkedHashSet<String>();
        names.add(target.memberName());
        names.add(target.simpleName());
        var relatedMethodKeys = relatedMethodKeys(target, targetParameterTypes);
        return scan(
                files,
                names,
                target,
                (parse, path) ->
                        matchesMemberReference(definitions, parse, path, target, targetParameterTypes, relatedMethodKeys));
    }

    private List<Location> findFieldReferencesScoped(
            DefinitionProvider definitions, DefinitionProvider.ResolvedSymbol target, String logicalKey) {
        var files = includeDeclarationFile(target.qualifiedType(), compiler.findTypeReferences(target.qualifiedType()));
        var names = relatedLogicalNames(target, logicalKey);
        return scan(files, names, target, (parse, path) -> matchesFieldReference(definitions, parse, path, logicalKey));
    }

    private List<Location> findLocalReferences(
            DefinitionProvider definitions, DefinitionProvider.ResolvedSymbol target) {
        return scan(
                new Path[] {file},
                Set.of(target.simpleName()),
                target,
                (parse, path) -> matchesLocalReference(definitions, parse, path, target));
    }

    private List<Location> scan(
            Path[] files, Set<String> names, DefinitionProvider.ResolvedSymbol target, ReferenceMatcher matcher) {
        var dedup = new LinkedHashMap<String, Location>();
        for (var candidate : files) {
            var parse = compiler.parse(candidate);
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitIdentifier(IdentifierTree tree, Void unused) {
                    maybeAdd(parse, getCurrentPath(), tree.getName().toString());
                    return super.visitIdentifier(tree, unused);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                    maybeAdd(parse, getCurrentPath(), tree.getIdentifier().toString());
                    return super.visitMemberSelect(tree, unused);
                }

                @Override
                public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
                    maybeAdd(parse, getCurrentPath(), tree.getName().toString());
                    return super.visitMemberReference(tree, unused);
                }

                @Override
                public Void visitMethod(MethodTree tree, Void unused) {
                    if (tree.getName() != null && !tree.getName().contentEquals("<init>")) {
                        maybeAdd(parse, getCurrentPath(), tree.getName().toString());
                    }
                    return super.visitMethod(tree, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree tree, Void unused) {
                    maybeAdd(parse, getCurrentPath(), simpleTreeName(tree.getIdentifier().toString()));
                    return super.visitNewClass(tree, unused);
                }

                private void maybeAdd(ParseTask parse, TreePath path, String name) {
                    if (!names.contains(name)) {
                        return;
                    }
                    if (!matcher.matches(parse, path)) {
                        return;
                    }
                    var location = FindHelper.locationStrict(parse, path, name);
                    if (location == null || (!includeDeclaration && isDeclarationLocation(location, target))) {
                        return;
                    }
                    dedup.putIfAbsent(key(location), location);
                }
            }.scan(parse.root(), null);
        }
        return new ArrayList<>(dedup.values());
    }

    private boolean matchesTypeReference(
            DefinitionProvider definitions, ParseTask parse, TreePath path, DefinitionProvider.ResolvedSymbol target) {
        var resolved = resolveOccurrence(definitions, parse, path);
        return resolved.qualifiedType() != null
                && Objects.equals(target.qualifiedType(), resolved.qualifiedType())
                && (resolved.memberName() == null
                        || (resolved.method() && Objects.equals(target.simpleName(), resolved.memberName())));
    }

    private boolean matchesMemberReference(
            DefinitionProvider definitions,
            ParseTask parse,
            TreePath path,
            DefinitionProvider.ResolvedSymbol target,
            List<String> targetParameterTypes,
            Set<String> relatedMethodKeys) {
        var resolved = resolveOccurrence(definitions, parse, path);
        if (isConstructorTarget(target)
                && Objects.equals(target.qualifiedType(), resolved.qualifiedType())
                && !resolved.method()
                && isConstructorUse(path)
                && signatureMatches(targetParameterTypes, occurrenceParameterTypes(parse, path))) {
            return true;
        }
        var resolvedKey = methodCanonicalKey(resolved, parse, path);
        if (!resolved.method()) {
            return false;
        }
        if (resolvedKey != null && relatedMethodKeys.contains(resolvedKey)) {
            return signatureMatches(targetParameterTypes, occurrenceParameterTypes(parse, path));
        }
        return methodOwnerMatches(target.qualifiedType(), resolved.qualifiedType())
                && Objects.equals(target.memberName(), resolved.memberName())
                && signatureMatches(targetParameterTypes, occurrenceParameterTypes(parse, path));
    }

    private boolean methodOwnerMatches(String targetOwner, String resolvedOwner) {
        if (Objects.equals(targetOwner, resolvedOwner)) {
            return true;
        }
        if (targetOwner == null || resolvedOwner == null) {
            return false;
        }
        return completionIndex.directSupertypes(resolvedOwner).contains(targetOwner)
                || completionIndex.directSupertypes(targetOwner).contains(resolvedOwner)
                || completionIndex.subtypes(targetOwner).contains(resolvedOwner)
                || completionIndex.subtypes(resolvedOwner).contains(targetOwner);
    }

    private boolean matchesFieldReference(
            DefinitionProvider definitions, ParseTask parse, TreePath path, String logicalKey) {
        var resolved = resolveOccurrence(definitions, parse, path);
        return Objects.equals(logicalKey, logicalKey(resolved));
    }

    private boolean matchesLocalReference(
            DefinitionProvider definitions, ParseTask parse, TreePath path, DefinitionProvider.ResolvedSymbol target) {
        var resolved = resolveOccurrence(definitions, parse, path);
        if (resolved.locations().isEmpty() || target.locations().isEmpty()) {
            return false;
        }
        return sameLocation(target.locations().get(0), resolved.locations().get(0));
    }

    private DefinitionProvider.ResolvedSymbol resolveOccurrence(
            DefinitionProvider definitions, ParseTask parse, TreePath path) {
        var cursor = Trees.instance(parse.task()).getSourcePositions().getStartPosition(parse.root(), path.getLeaf());
        if (cursor < 0) {
            cursor = parse.root().getLineMap().getPosition(1, 1);
        }
        return definitions.resolve(parse, path, cursor + 1);
    }

    private boolean isDeclarationLocation(Location location, DefinitionProvider.ResolvedSymbol target) {
        return !target.locations().isEmpty() && sameLocation(location, target.locations().get(0));
    }

    private List<String> targetParameterTypes(DefinitionProvider.ResolvedSymbol target) {
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
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        var path = new org.javacs.FindNameAt(parse).scan(parse.root(), cursor);
        if (path == null || !(path.getLeaf() instanceof MethodTree method)) {
            return List.of();
        }
        return declaredParameterTypes(parse, path, method);
    }

    private List<String> occurrenceParameterTypes(ParseTask parse, TreePath path) {
        var leaf = path.getLeaf();
        if (leaf instanceof NewClassTree newClassTree) {
            return argumentTypes(parse, path, newClassTree.getArguments());
        }
        var parent = path.getParentPath() != null ? path.getParentPath().getLeaf() : null;
        if (parent instanceof NewClassTree newClassTree) {
            return argumentTypes(parse, path.getParentPath(), newClassTree.getArguments());
        }
        if (parent instanceof MethodInvocationTree invocation) {
            return argumentTypes(parse, path.getParentPath(), invocation.getArguments());
        }
        return List.of();
    }

    private List<String> declaredParameterTypes(ParseTask parse, TreePath path, MethodTree method) {
        var cursor = Trees.instance(parse.task()).getSourcePositions().getStartPosition(parse.root(), path.getLeaf()) + 1;
        var resolver = new ParseTypeResolver(parse, completionIndex, compiler, cursor);
        var result = new ArrayList<String>(method.getParameters().size());
        for (VariableTree parameter : method.getParameters()) {
            if (parameter.getType() == null) {
                return List.of();
            }
            var resolved = resolver.resolveTypeTree(parameter.getType(), false);
            result.add(canonicalType(resolved.map(ParseTypeResolver.TypeResolution::qualifiedType).orElse(parameter.getType().toString())));
        }
        return result;
    }

    private List<String> argumentTypes(ParseTask parse, TreePath path, List<? extends ExpressionTree> arguments) {
        var cursor = Trees.instance(parse.task()).getSourcePositions().getStartPosition(parse.root(), path.getLeaf()) + 1;
        var resolver = new ParseTypeResolver(parse, completionIndex, compiler, cursor);
        var result = new ArrayList<String>(arguments.size());
        for (var argument : arguments) {
            var resolved = resolver.resolveExpression(argument);
            result.add(canonicalType(resolved.map(ParseTypeResolver.TypeResolution::qualifiedType).orElse("")));
        }
        return result;
    }

    private boolean signatureMatches(List<String> targetParameterTypes, List<String> occurrenceParameterTypes) {
        if (targetParameterTypes.isEmpty() || occurrenceParameterTypes.isEmpty()) {
            return true;
        }
        if (targetParameterTypes.stream().anyMatch(String::isBlank)
                || occurrenceParameterTypes.stream().anyMatch(String::isBlank)) {
            return true;
        }
        return targetParameterTypes.equals(occurrenceParameterTypes);
    }

    private boolean isConstructorTarget(DefinitionProvider.ResolvedSymbol target) {
        return target.method() && Objects.equals(target.memberName(), target.simpleName());
    }

    private boolean isConstructorUse(TreePath path) {
        if (path == null) {
            return false;
        }
        if (path.getLeaf() instanceof NewClassTree) {
            return true;
        }
        var parent = path.getParentPath() != null ? path.getParentPath().getLeaf() : null;
        return parent instanceof NewClassTree || parent instanceof MemberReferenceTree;
    }

    private boolean sameLocation(Location left, Location right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.uri, right.uri)
                && left.range.start.line == right.range.start.line
                && left.range.start.character == right.range.start.character
                && left.range.end.line == right.range.end.line
                && left.range.end.character == right.range.end.character;
    }

    private String key(Location location) {
        return location.uri + ":" + location.range.start.line + ":" + location.range.start.character + ":"
                + location.range.end.line + ":" + location.range.end.character;
    }

    private Set<String> relatedMethodKeys(
            DefinitionProvider.ResolvedSymbol target, List<String> targetParameterTypes) {
        if (target.indexMember() != null) {
            String[] erasedParameterTypes = target.indexMember().erasedParameterTypes == null
                    ? new String[0]
                    : target.indexMember().erasedParameterTypes;
            if (completionIndex.isWorkspaceOwnedType(target.indexMember().ownerType, compiler)) {
                return completionIndex.workspace().relatedMethodKeys(
                        target.indexMember().ownerType,
                        target.indexMember().name,
                        erasedParameterTypes);
            }
            return completionIndex.relatedMethodKeys(
                    target.indexMember().ownerType,
                    target.indexMember().name,
                    erasedParameterTypes);
        }
        if (completionIndex.isWorkspaceOwnedType(target.qualifiedType(), compiler)) {
            return completionIndex.workspace().relatedMethodKeys(
                    target.qualifiedType(), target.memberName(), targetParameterTypes.toArray(String[]::new));
        }
        return completionIndex.relatedMethodKeys(
                target.qualifiedType(), target.memberName(), targetParameterTypes.toArray(String[]::new));
    }

    private Optional<String> fieldLogicalKey(DefinitionProvider.ResolvedSymbol target) {
        if (target == null || target.qualifiedType() == null || target.memberName() == null) {
            return Optional.empty();
        }
        if (target.indexMember() != null && target.indexMember().logicalKey != null) {
            if (!Objects.equals(target.indexMember().logicalKey, target.indexMember().canonicalKey)
                    || !target.method()) {
                return Optional.of(target.indexMember().logicalKey);
            }
        }
        if (!target.method()) {
            return Optional.of(
                    TypeMemberIndex.canonicalMemberKey(
                            target.qualifiedType(), org.javacs.lsp.CompletionItemKind.Field, target.memberName(), null));
        }
        return Optional.empty();
    }

    private Set<String> relatedLogicalNames(DefinitionProvider.ResolvedSymbol target, String logicalKey) {
        var names = new LinkedHashSet<String>();
        if (target.memberName() != null) {
            names.add(target.memberName());
            names.addAll(accessorNames(target.memberName()));
        }
        completionIndex.typeInfo(target.qualifiedType())
                .ifPresent(
                        info ->
                                info.members.stream()
                                        .filter(member -> Objects.equals(logicalKey, member.logicalKey))
                                        .forEach(member -> names.add(member.name)));
        return names;
    }

    private String logicalKey(DefinitionProvider.ResolvedSymbol symbol) {
        if (symbol.indexMember() != null && symbol.indexMember().logicalKey != null) {
            return symbol.indexMember().logicalKey;
        }
        if (!symbol.method() && symbol.qualifiedType() != null && symbol.memberName() != null) {
            return TypeMemberIndex.canonicalMemberKey(
                    symbol.qualifiedType(), org.javacs.lsp.CompletionItemKind.Field, symbol.memberName(), null);
        }
        return null;
    }

    private String methodCanonicalKey(
            DefinitionProvider.ResolvedSymbol resolved, ParseTask parse, TreePath path) {
        if (resolved.indexMember() != null && resolved.indexMember().canonicalKey != null) {
            return resolved.indexMember().canonicalKey;
        }
        if (!resolved.method() || resolved.qualifiedType() == null || resolved.memberName() == null) {
            return null;
        }
        List<String> parameterTypes;
        if (path.getLeaf() instanceof MethodTree method) {
            parameterTypes = declaredParameterTypes(parse, path, method);
        } else if (path.getParentPath() != null && path.getParentPath().getLeaf() instanceof MethodTree method) {
            parameterTypes = declaredParameterTypes(parse, path.getParentPath(), method);
        } else {
            parameterTypes = occurrenceParameterTypes(parse, path);
        }
        return TypeMemberIndex.canonicalMemberKey(
                resolved.qualifiedType(), org.javacs.lsp.CompletionItemKind.Method, resolved.memberName(), parameterTypes.toArray(String[]::new));
    }

    private Path[] includeDeclarationFile(String qualifiedType, Path[] files) {
        var classFile = compiler.findTypeDeclaration(qualifiedType);
        if (classFile == null || classFile.equals(CompilerProvider.NOT_FOUND)) {
            return files;
        }
        var combined = new ArrayList<>(Arrays.asList(files));
        if (!combined.contains(classFile)) {
            combined.add(classFile);
        }
        return combined.toArray(Path[]::new);
    }

    private Set<String> accessorNames(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return Set.of();
        }
        var base = fieldName.substring(0, 1).toUpperCase(Locale.ROOT) + fieldName.substring(1);
        return Set.of("get" + base, "is" + base, "set" + base);
    }

    private String simpleTreeName(String raw) {
        var genericStart = raw.indexOf('<');
        if (genericStart >= 0) {
            raw = raw.substring(0, genericStart);
        }
        var dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private String canonicalType(String typeName) {
        var raw = typeName == null ? "" : typeName.trim();
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        return switch (raw) {
            case "byte", "java.lang.Byte" -> "java.lang.Byte";
            case "short", "java.lang.Short" -> "java.lang.Short";
            case "int", "java.lang.Integer" -> "java.lang.Integer";
            case "long", "java.lang.Long" -> "java.lang.Long";
            case "float", "java.lang.Float" -> "java.lang.Float";
            case "double", "java.lang.Double" -> "java.lang.Double";
            case "boolean", "java.lang.Boolean" -> "java.lang.Boolean";
            case "char", "java.lang.Character" -> "java.lang.Character";
            default -> raw;
        };
    }

    @FunctionalInterface
    private interface ReferenceMatcher {
        boolean matches(ParseTask parse, TreePath path);
    }
}
