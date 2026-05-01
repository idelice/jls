package org.javacs.provider;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.nio.file.Path;
import java.util.*;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.ParseTask;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.Location;
import org.javacs.navigation.NavigationSymbolSupport;
import org.javacs.navigation.SymbolIdentity;
import org.javacs.navigation.SymbolIdentityResolver;
import org.javacs.resolve.TypeNames;

/**
 * Find references by resolving the target symbol once through {@link SymbolIdentityResolver} and
 * then matching occurrences through shared navigation-side symbol keys.
 */
public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndexRouter;
    private final SymbolIdentityResolver resolver;
    private final Path file;
    private final boolean includeDeclaration;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public ReferenceProvider(
            CompilerProvider compiler,
            TypeIndexRouter typeIndexRouter,
            SymbolIdentityResolver resolver,
            Path file,
            boolean includeDeclaration) {
        this.compiler = compiler;
        this.typeIndexRouter = typeIndexRouter == null ? TypeIndexRouter.EMPTY : typeIndexRouter;
        this.resolver = resolver;
        this.file = file;
        this.includeDeclaration = includeDeclaration;
    }

    public List<Location> find() {
        var target = resolver.resolveTarget();
        if (!isSupported(target)) {
            return NOT_SUPPORTED;
        }
        if (target.qualifiedType() == null) {
            return findLocalReferences(target);
        }
        if (target.memberName() == null) {
            return findTypeReferences(target);
        }
        var fieldLogicalKey = NavigationSymbolSupport.fieldLogicalKey(target);
        if (fieldLogicalKey.isPresent()) {
            return findFieldReferencesScoped(target, fieldLogicalKey.get());
        }
        return findMemberReferences(
                target,
                NavigationSymbolSupport.targetParameterTypes(compiler, typeIndexRouter, target));
    }

    private boolean isSupported(SymbolIdentity target) {
        return target != null
                && (target.declarationLocation().isPresent()
                        || target.qualifiedType() != null
                        || target.memberName() != null);
    }

    private List<Location> findTypeReferences(SymbolIdentity target) {
        var files = includeDeclarationFile(target.qualifiedType(), compiler.findTypeReferences(target.qualifiedType()));
        return scan(
                files,
                Set.of(target.simpleName()),
                target,
                (parse, path) -> matchesTypeReference(parse, path, target));
    }

    private List<Location> findMemberReferences(SymbolIdentity target, List<String> targetParameterTypes) {
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
                        matchesMemberReference(parse, path, target, targetParameterTypes, relatedMethodKeys));
    }

    private List<Location> findFieldReferencesScoped(SymbolIdentity target, String logicalKey) {
        var files = includeDeclarationFile(target.qualifiedType(), compiler.findTypeReferences(target.qualifiedType()));
        var names = relatedLogicalNames(target, logicalKey);
        return scan(files, names, target, (parse, path) -> matchesFieldReference(parse, path, logicalKey));
    }

    private List<Location> findLocalReferences(SymbolIdentity target) {
        return scan(
                new Path[] {file},
                Set.of(target.simpleName()),
                target,
                (parse, path) -> matchesLocalReference(parse, path, target));
    }

    private List<Location> scan(
            Path[] files, Set<String> names, SymbolIdentity target, ReferenceMatcher matcher) {
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

    private boolean matchesTypeReference(ParseTask parse, TreePath path, SymbolIdentity target) {
        var resolved = resolver.resolveOccurrence(parse, path);
        return resolved.qualifiedType() != null
                && Objects.equals(target.qualifiedType(), resolved.qualifiedType())
                && (resolved.memberName() == null
                        || (resolved.method() && Objects.equals(target.simpleName(), resolved.memberName())));
    }

    private boolean matchesMemberReference(
            ParseTask parse,
            TreePath path,
            SymbolIdentity target,
            List<String> targetParameterTypes,
            Set<String> relatedMethodKeys) {
        var resolved = resolver.resolveOccurrence(parse, path);
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

    private boolean matchesFieldReference(ParseTask parse, TreePath path, String logicalKey) {
        var resolved = resolver.resolveOccurrence(parse, path);
        return Objects.equals(logicalKey, NavigationSymbolSupport.logicalKey(resolved));
    }

    private boolean matchesLocalReference(ParseTask parse, TreePath path, SymbolIdentity target) {
        var resolved = resolver.resolveOccurrence(parse, path);
        if (resolved.declarationLocation().isEmpty() || target.declarationLocation().isEmpty()) {
            return false;
        }
        return sameLocation(target.declarationLocation().get(), resolved.declarationLocation().get());
    }

    private boolean isDeclarationLocation(Location location, SymbolIdentity target) {
        return target.declarationLocation().isPresent()
                && sameLocation(location, target.declarationLocation().get());
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

    private boolean isConstructorTarget(SymbolIdentity target) {
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

    private Set<String> relatedMethodKeys(SymbolIdentity target, List<String> targetParameterTypes) {
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

    private Set<String> relatedLogicalNames(SymbolIdentity target, String logicalKey) {
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
