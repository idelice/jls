package org.javacs.provider;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.*;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.ParseTask;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.Location;
import org.javacs.navigation.NavigationSymbolSupport;
import org.javacs.resolve.ParseTypeResolver;
import org.javacs.resolve.TypeNames;

/**
 * Find references by resolving the target symbol once through {@link DefinitionProvider} and then
 * matching occurrences through shared navigation-side symbol keys.
 */
public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndexRouter;
    private final Path file;
    private final int line, column;
    private final boolean includeDeclaration;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public ReferenceProvider(CompilerProvider compiler, Path file, int line, int column) {
        this(compiler, TypeIndexRouter.EMPTY, file, line, column, false);
    }

    public ReferenceProvider(CompilerProvider compiler, TypeIndexRouter typeIndexRouter, Path file, int line, int column) {
        this(compiler, typeIndexRouter, file, line, column, false);
    }

    public ReferenceProvider(
            CompilerProvider compiler,
            TypeIndexRouter typeIndexRouter,
            Path file,
            int line,
            int column,
            boolean includeDeclaration) {
        this.compiler = compiler;
        this.typeIndexRouter = typeIndexRouter == null ? TypeIndexRouter.EMPTY : typeIndexRouter;
        this.file = file;
        this.line = line;
        this.column = column;
        this.includeDeclaration = includeDeclaration;
    }

    public List<Location> find() {
        var definitions = new DefinitionProvider(compiler, typeIndexRouter, file, line, column);
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
        var fieldLogicalKey = NavigationSymbolSupport.fieldLogicalKey(target);
        if (fieldLogicalKey.isPresent()) {
            return findFieldReferencesScoped(definitions, target, fieldLogicalKey.get());
        }
        return findMemberReferences(
                definitions,
                target,
                NavigationSymbolSupport.targetParameterTypes(compiler, typeIndexRouter, target));
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
                    maybeAdd(parse, getCurrentPath(), TypeNames.simpleName(tree.getIdentifier().toString()));
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
                && signatureMatches(
                        targetParameterTypes,
                        NavigationSymbolSupport.occurrenceParameterTypes(parse, path, typeIndexRouter, compiler))) {
            return true;
        }
        var resolvedKey =
                NavigationSymbolSupport.methodCanonicalKey(
                        resolved, parse, path, typeIndexRouter, compiler);
        if (!resolved.method()) {
            return false;
        }
        if (resolvedKey != null && relatedMethodKeys.contains(resolvedKey)) {
            return signatureMatches(
                    targetParameterTypes,
                    NavigationSymbolSupport.occurrenceParameterTypes(parse, path, typeIndexRouter, compiler));
        }
        return methodOwnerMatches(target.qualifiedType(), resolved.qualifiedType())
                && Objects.equals(target.memberName(), resolved.memberName())
                && signatureMatches(
                        targetParameterTypes,
                        NavigationSymbolSupport.occurrenceParameterTypes(parse, path, typeIndexRouter, compiler));
    }

    private boolean methodOwnerMatches(String targetOwner, String resolvedOwner) {
        if (Objects.equals(targetOwner, resolvedOwner)) {
            return true;
        }
        if (targetOwner == null || resolvedOwner == null) {
            return false;
        }
        return typeIndexRouter.directSupertypes(resolvedOwner).contains(targetOwner)
                || typeIndexRouter.directSupertypes(targetOwner).contains(resolvedOwner)
                || typeIndexRouter.workspaceSubTypes(targetOwner).contains(resolvedOwner)
                || typeIndexRouter.workspaceSubTypes(resolvedOwner).contains(targetOwner);
    }

    private boolean matchesFieldReference(
            DefinitionProvider definitions, ParseTask parse, TreePath path, String logicalKey) {
        var resolved = resolveOccurrence(definitions, parse, path);
        return Objects.equals(logicalKey, NavigationSymbolSupport.logicalKey(resolved));
    }

    private boolean matchesLocalReference(
            DefinitionProvider definitions, ParseTask parse, TreePath path, DefinitionProvider.ResolvedSymbol target) {
        var resolved = resolveOccurrence(definitions, parse, path);
        if (resolved.locations().isEmpty() || target.locations().isEmpty()) {
            return false;
        }
        return sameLocation(target.locations().getFirst(), resolved.locations().getFirst());
    }

    private DefinitionProvider.ResolvedSymbol resolveOccurrence(
            DefinitionProvider definitions, ParseTask parse, TreePath path) {
        var cursor = Trees.instance(parse.task()).getSourcePositions().getStartPosition(parse.root(), path.getLeaf());
        if (cursor < 0) {
            cursor = parse.root().getLineMap().getPosition(1, 1);
        }
        return definitions.resolve(
                parse, path, new ParseTypeResolver(parse, compiler, typeIndexRouter, cursor + 1));
    }

    private boolean isDeclarationLocation(Location location, DefinitionProvider.ResolvedSymbol target) {
        return !target.locations().isEmpty() && sameLocation(location, target.locations().get(0));
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
            if (typeIndexRouter.isWorkspaceOwnedType(target.indexMember().ownerType)) {
                return typeIndexRouter.workspace().relatedMethodKeys(
                        target.indexMember().ownerType,
                        target.indexMember().name,
                        erasedParameterTypes);
            }
            return typeIndexRouter.relatedMethodKeys(
                    target.indexMember().ownerType,
                    target.indexMember().name,
                    erasedParameterTypes);
        }
        if (typeIndexRouter.isWorkspaceOwnedType(target.qualifiedType())) {
            return typeIndexRouter.workspace().relatedMethodKeys(
                    target.qualifiedType(), target.memberName(), targetParameterTypes.toArray(String[]::new));
        }
        return typeIndexRouter.relatedMethodKeys(
                target.qualifiedType(), target.memberName(), targetParameterTypes.toArray(String[]::new));
    }

    private Set<String> relatedLogicalNames(DefinitionProvider.ResolvedSymbol target, String logicalKey) {
        var names = new LinkedHashSet<String>();
        if (target.memberName() != null) {
            names.add(target.memberName());
            names.addAll(NavigationSymbolSupport.accessorNames(target.memberName()));
        }
        typeIndexRouter.typeInfo(target.qualifiedType())
                .ifPresent(
                        info ->
                                info.members.stream()
                                        .filter(member -> Objects.equals(logicalKey, member.logicalKey))
                                        .forEach(member -> names.add(member.name)));
        return names;
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

    @FunctionalInterface
    private interface ReferenceMatcher {
        boolean matches(ParseTask parse, TreePath path);
    }
}
