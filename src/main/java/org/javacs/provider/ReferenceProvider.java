package org.javacs.provider;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.*;
import org.javacs.*;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.Location;
import org.javacs.navigation.NavigationSymbolSupport;
import org.javacs.navigation.SymbolIdentity;
import org.javacs.resolve.ParseTypeResolver;
import org.javacs.resolve.TypeNames;

/**
 * Find references by resolving the target symbol once through ATTR compilation and then matching
 * occurrences through shared navigation-side symbol keys.
 *
 * <p>{@link ParseTypeResolver} is used ONLY for {@link MemberSelectTree} receiver type resolution
 * and {@link MemberReferenceTree} method reference target resolution — matching the pattern from
 * {@link SignatureProvider}. All identifier resolution uses simpler, cursor-independent approaches.
 */
public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final TypeIndexRouter typeIndexRouter;
    private final Path file;
    private final int line;
    private final int column;
    private final boolean includeDeclaration;

    public static final List<Location> NOT_SUPPORTED = List.of();

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");

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
        var target = resolveTarget();
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

    private SymbolIdentity resolveTarget() {
        var defProvider = new DefinitionProvider(compiler, typeIndexRouter, file, line, column);
        var resolved = defProvider.resolveSymbol();
        return toSymbolIdentity(resolved);
    }

    private static SymbolIdentity toSymbolIdentity(DefinitionProvider.ResolvedSymbol resolved) {
        if (resolved == null || resolved.locations().isEmpty()) {
            return SymbolIdentity.unsupported(resolved != null ? resolved.simpleName() : null);
        }
        return new SymbolIdentity(
                resolved.qualifiedType(),
                resolved.memberName(),
                resolved.method(),
                resolved.indexMember(),
                resolved.simpleName(),
                Optional.of(resolved.locations().getFirst()));
    }

    private SymbolIdentity resolveIdentifierTargetIdentity(
            ParseTask parse, TreePath path, IdentifierTree identifier) {
        return resolveIdentifierOccurrence(parse, path, identifier);
    }

    private SymbolIdentity resolveOccurrence(ParseTask parse, TreePath path) {
        var leaf = path.getLeaf();
        if (leaf instanceof IdentifierTree id) {
            return resolveIdentifierOccurrence(parse, path, id);
        }
        if (leaf instanceof MemberSelectTree ms) {
            return resolveMemberSelectOccurrence(parse, path, ms);
        }
        if (leaf instanceof MemberReferenceTree mr) {
            return resolveMemberReferenceIdentity(mr, parse, path);
        }
        var name = selectedName(path);
        if (name != null && leaf instanceof MethodTree) {
            return resolveIdentifierOccurrence(parse, path, null);
        }
        return SymbolIdentity.unsupported(null);
    }

    private SymbolIdentity resolveIdentifierOccurrence(
            ParseTask parse, TreePath path, IdentifierTree id) {
        var name = id != null ? id.getName().toString() : selectedName(path);

        var localLoc = findLocalDeclarationLocation(parse, path, name);
        if (localLoc.isPresent()) {
            return new SymbolIdentity(
                    null, name, false, null, name, localLoc);
        }

        var parent = path.getParentPath() != null ? path.getParentPath().getLeaf() : null;
        if (id != null
                && parent instanceof NewClassTree nc
                && nc.getIdentifier() == id
                && nc.getClassBody() == null) {
            var resolved =
                    typeIndexRouter.resolveType(
                            nc.getIdentifier().toString(), parse.root());
            return new SymbolIdentity(
                    resolved.map(t -> t.qualifiedName).orElse(null),
                    name,
                    true,
                    null,
                    name,
                    Optional.empty());
        }

        if (id != null
                && parent instanceof MethodInvocationTree inv
                && inv.getMethodSelect() == id) {
            var thisType = resolveEnclosingTypeName(path, parse);
            if (thisType.isPresent()) {
                var member = typeIndexRouter.ownerMember(thisType.get(), name, false);
                return new SymbolIdentity(
                        thisType.get(), name, true, member.orElse(null), name, Optional.empty());
            }
            return new SymbolIdentity(
                    null, name, true, null, name, Optional.empty());
        }

        var astMember = resolveMemberFromParse(parse, path, name);
        if (astMember != null) return astMember;

        var resolvedType = typeIndexRouter.resolveType(name, parse.root());
        if (resolvedType.isPresent()) {
            return new SymbolIdentity(
                    resolvedType.get().qualifiedName,
                    null,
                    false,
                    null,
                    name,
                    Optional.empty());
        }

        var thisType = resolveEnclosingTypeName(path, parse);
        if (thisType.isPresent()) {
            var field = typeIndexRouter.ownerMember(thisType.get(), name, false);
            if (field.isPresent() && field.get().kind == CompletionItemKind.Field) {
                return new SymbolIdentity(
                        thisType.get(), name, false, field.get(), name, Optional.empty());
            }
            field = typeIndexRouter.ownerMember(thisType.get(), name, true);
            if (field.isPresent() && field.get().kind == CompletionItemKind.Field) {
                return new SymbolIdentity(
                        thisType.get(), name, false, field.get(), name, Optional.empty());
            }
        }

        return SymbolIdentity.unsupported(name);
    }

    private SymbolIdentity resolveMemberSelectTargetIdentity(
            ParseTask parse, TreePath path, MemberSelectTree memberSelect) {
        return resolveMemberSelectOccurrence(parse, path, memberSelect);
    }

    private SymbolIdentity resolveMemberSelectOccurrence(
            ParseTask parse, TreePath path, MemberSelectTree ms) {
        var memberName = ms.getIdentifier().toString();
        var cursor = startOffset(parse, path) + 1;
        var types =
                new ParseTypeResolver(parse, compiler, typeIndexRouter, cursor);
        var qualifiedType = tryResolveExpressionType(parse, path, ms.getExpression(), types);
        if (qualifiedType != null) {
            // If the MemberSelectTree is directly in a method call, mark as method=true
            var parent = path.getParentPath() != null ? path.getParentPath().getLeaf() : null;
            if (parent instanceof MethodInvocationTree inv && inv.getMethodSelect() == ms) {
                return new SymbolIdentity(
                        qualifiedType, memberName, true, null, memberName, Optional.empty());
            }
            var resolvedType = typeIndexRouter.resolveType(qualifiedType, parse.root());
            var isType = resolvedType.isPresent();
            return new SymbolIdentity(
                    qualifiedType, memberName, !isType, null, memberName, Optional.empty());
        }
        var resolvedType = typeIndexRouter.resolveType(memberName, parse.root());
        if (resolvedType.isPresent()) {
            return new SymbolIdentity(
                    resolvedType.get().qualifiedName, null, false, null, memberName, Optional.empty());
        }
        var astMember = resolveMemberFromParse(parse, path, memberName);
        if (astMember != null) return astMember;
        return SymbolIdentity.unsupported(memberName);
    }

    private SymbolIdentity resolveMemberReferenceIdentity(
            MemberReferenceTree memberReference, ParseTask parse, TreePath path) {
        var cursor = startOffset(parse, path) + 1;
        var types =
                new ParseTypeResolver(parse, compiler, typeIndexRouter, cursor);
        var name = memberReference.getName().toString();
        var qualifier = memberReference.getQualifierExpression();
        if (qualifier != null) {
            var resolved = types.resolveExpression(qualifier);
            if (resolved.isPresent()) {
                return new SymbolIdentity(
                        resolved.get().qualifiedType(),
                        name,
                        true,
                        null,
                        name,
                        Optional.empty());
            }
        }
        var resolvedType = typeIndexRouter.resolveType(name, parse.root());
        if (resolvedType.isPresent()) {
            return new SymbolIdentity(
                    resolvedType.get().qualifiedName, name, true, null, name, Optional.empty());
        }
        return new SymbolIdentity(null, name, true, null, name, Optional.empty());
    }

    private List<Location> findTypeReferences(SymbolIdentity target) {
        var files =
                includeDeclarationFile(
                        target.qualifiedType(),
                        compiler.findTypeReferences(target.qualifiedType()));
        return scan(
                files,
                Set.of(target.simpleName()),
                target,
                (parse, path) -> matchesTypeReference(parse, path, target));
    }

    private List<Location> findMemberReferences(
            SymbolIdentity target, List<String> targetParameterTypes) {
        var files =
                compiler.findMemberReferences(
                        target.qualifiedType(), target.memberName());
        var names = new java.util.LinkedHashSet<String>();
        names.add(target.memberName());
        names.add(target.simpleName());
        var relatedMethodKeys = relatedMethodKeys(target, targetParameterTypes);
        return scan(
                files,
                names,
                target,
                (parse, path) ->
                        matchesMemberReference(
                                parse, path, target, targetParameterTypes, relatedMethodKeys));
    }

    private List<Location> findFieldReferencesScoped(
            SymbolIdentity target, String logicalKey) {
        var files =
                includeDeclarationFile(
                        target.qualifiedType(),
                        compiler.findTypeReferences(target.qualifiedType()));
        var names = relatedLogicalNames(target, logicalKey);
        return scan(
                files,
                names,
                target,
                (parse, path) -> matchesFieldReference(parse, path, logicalKey));
    }

    private List<Location> findLocalReferences(SymbolIdentity target) {
        return scan(
                new Path[] {file},
                Set.of(target.simpleName()),
                target,
                (parse, path) -> matchesLocalReference(parse, path, target));
    }

    private List<Location> scan(
            Path[] files,
            Set<String> names,
            SymbolIdentity target,
            ReferenceMatcher matcher) {
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
                    maybeAdd(
                            parse,
                            getCurrentPath(),
                            TypeNames.simpleName(tree.getIdentifier().toString()));
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
                    if (location == null
                            || (!includeDeclaration
                                    && isDeclarationLocation(location, target))) {
                        return;
                    }
                    dedup.putIfAbsent(key(location), location);
                }
            }.scan(parse.root(), null);
        }
        return new ArrayList<>(dedup.values());
    }

    private boolean matchesTypeReference(
            ParseTask parse, TreePath path, SymbolIdentity target) {
        var resolved = resolveOccurrence(parse, path);
        return resolved.qualifiedType() != null
                && Objects.equals(target.qualifiedType(), resolved.qualifiedType())
                && (resolved.memberName() == null
                        || (resolved.method()
                                && Objects.equals(
                                        target.simpleName(), resolved.memberName())));
    }

    private boolean matchesMemberReference(
            ParseTask parse,
            TreePath path,
            SymbolIdentity target,
            List<String> targetParameterTypes,
            Set<String> relatedMethodKeys) {
        var resolved = resolveOccurrence(parse, path);
        if (isConstructorTarget(target)
                && Objects.equals(target.qualifiedType(), resolved.qualifiedType())
                && !resolved.method()
                && isConstructorUse(path)
                && signatureMatches(
                        targetParameterTypes,
                        NavigationSymbolSupport.occurrenceParameterTypes(
                                parse, path, typeIndexRouter, compiler))) {
            LOG.fine(String.format("[ref] accept_ctor path=%s target=%s.%s resolved=%s.%s",
                    path.getCompilationUnit().getSourceFile().getName(),
                    target.qualifiedType(), target.memberName(),
                    resolved.qualifiedType(), resolved.memberName()));
            return true;
        }
        var resolvedKey =
                NavigationSymbolSupport.methodCanonicalKey(
                        resolved, parse, path, typeIndexRouter, compiler);
        if (!resolved.method()) {
            LOG.fine(String.format("[ref] reject_not_method path=%s target=%s.%s resolved=%s.%s",
                    path.getCompilationUnit().getSourceFile().getName(),
                    target.qualifiedType(), target.memberName(),
                    resolved.qualifiedType(), resolved.memberName()));
            return false;
        }
        var ownerMatch = methodOwnerMatches(target.qualifiedType(), resolved.qualifiedType());
        var nameMatch = Objects.equals(target.memberName(), resolved.memberName());
        LOG.fine(String.format("[ref] matches_member path=%s target=%s.%s resolved=%s.%s owner=%s name=%s",
                path.getCompilationUnit().getSourceFile().getName(),
                target.qualifiedType(), target.memberName(),
                resolved.qualifiedType(), resolved.memberName(),
                ownerMatch, nameMatch));
        if (ownerMatch && nameMatch) {
            return signatureMatches(
                    targetParameterTypes,
                    NavigationSymbolSupport.occurrenceParameterTypes(
                            parse, path, typeIndexRouter, compiler));
        }
        return false;
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
            ParseTask parse, TreePath path, String logicalKey) {
        var resolved = resolveOccurrence(parse, path);
        return Objects.equals(logicalKey, NavigationSymbolSupport.logicalKey(resolved));
    }

    private boolean matchesLocalReference(
            ParseTask parse, TreePath path, SymbolIdentity target) {
        var resolved = resolveOccurrence(parse, path);
        if (resolved.declarationLocation().isEmpty()
                || target.declarationLocation().isEmpty()) {
            return false;
        }
        return sameLocation(
                target.declarationLocation().get(),
                resolved.declarationLocation().get());
    }

    private boolean isDeclarationLocation(Location location, SymbolIdentity target) {
        return target.declarationLocation().isPresent()
                && sameLocation(location, target.declarationLocation().get());
    }

    private boolean signatureMatches(
            List<String> targetParameterTypes, List<String> occurrenceParameterTypes) {
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
        var parent =
                path.getParentPath() != null ? path.getParentPath().getLeaf() : null;
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
        return location.uri
                + ":"
                + location.range.start.line
                + ":"
                + location.range.start.character
                + ":"
                + location.range.end.line
                + ":"
                + location.range.end.character;
    }

    private Set<String> relatedMethodKeys(
            SymbolIdentity target, List<String> targetParameterTypes) {
        if (target.indexMember() != null) {
            String[] erasedParameterTypes =
                    target.indexMember().erasedParameterTypes == null
                            ? new String[0]
                            : target.indexMember().erasedParameterTypes;
            if (typeIndexRouter.isWorkspaceOwnedType(
                    target.indexMember().ownerType)) {
                return typeIndexRouter
                        .workspace()
                        .relatedMethodKeys(
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
            return typeIndexRouter
                    .workspace()
                    .relatedMethodKeys(
                            target.qualifiedType(),
                            target.memberName(),
                            targetParameterTypes.toArray(String[]::new));
        }
        return typeIndexRouter.relatedMethodKeys(
                target.qualifiedType(),
                target.memberName(),
                targetParameterTypes.toArray(String[]::new));
    }

    private Set<String> relatedLogicalNames(SymbolIdentity target, String logicalKey) {
        var names = new LinkedHashSet<String>();
        if (target.memberName() != null) {
            names.add(target.memberName());
            names.addAll(NavigationSymbolSupport.accessorNames(target.memberName()));
        }
        typeIndexRouter
                .typeInfo(target.qualifiedType())
                .ifPresent(
                        info ->
                                info.members.stream()
                                        .filter(
                                                member ->
                                                        Objects.equals(
                                                                logicalKey,
                                                                member.logicalKey))
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

    private Optional<Location> findLocalDeclarationLocation(
            ParseTask parse, TreePath path, String name) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            var leaf = cursor.getLeaf();

            if (leaf instanceof MethodTree method) {
                for (var param : method.getParameters()) {
                    if (param.getName().contentEquals(name)) {
                        return Optional.ofNullable(FindHelper.locationStrict(parse, cursor, name));
                    }
                }
            }

            if (leaf instanceof BlockTree) {
                var blockStatements = ((BlockTree) cursor.getLeaf()).getStatements();
                for (var stmt : blockStatements) {
                    if (stmt instanceof VariableTree vt && vt.getName().contentEquals(name)) {
                        var stmtPath = new TreePath(cursor, stmt);
                        return Optional.ofNullable(FindHelper.locationStrict(parse, stmtPath, name));
                    }
                }
            }

            if (leaf instanceof CatchTree ct) {
                if (ct.getParameter() != null
                        && ct.getParameter().getName() != null
                        && ct.getParameter().getName().contentEquals(name)) {
                    var paramPath = new TreePath(cursor, ct.getParameter());
                    return Optional.ofNullable(FindHelper.locationStrict(parse, paramPath, name));
                }
            }

            if (leaf instanceof EnhancedForLoopTree efl) {
                if (efl.getVariable() != null
                        && efl.getVariable().getName() != null
                        && efl.getVariable().getName().contentEquals(name)) {
                    var varPath = new TreePath(cursor, efl.getVariable());
                    return Optional.ofNullable(FindHelper.locationStrict(parse, varPath, name));
                }
            }

            if (leaf instanceof TryTree tt) {
                for (var resource : tt.getResources()) {
                    if (resource instanceof VariableTree vt && vt.getName().contentEquals(name)) {
                        var resPath = new TreePath(cursor, resource);
                        return Optional.ofNullable(FindHelper.locationStrict(parse, resPath, name));
                    }
                }
            }

            if (leaf instanceof MethodTree || leaf instanceof ClassTree) {
                break;
            }
        }
        return Optional.empty();
    }

    private SymbolIdentity resolveMemberFromParse(
            ParseTask parse, TreePath path, String name) {
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (!(cursor.getLeaf() instanceof ClassTree classTree)) continue;

            var encTypeName = enclosingTypeNameFromParse(parse, cursor);
            if (encTypeName.isEmpty()) continue;

            for (var member : classTree.getMembers()) {
                if (member instanceof VariableTree vt && vt.getName().contentEquals(name)) {
                    var loc = FindHelper.locationStrict(parse, new TreePath(cursor, member), name);
                    return new SymbolIdentity(
                            encTypeName.get(),
                            name,
                            false,
                            null,
                            name,
                            Optional.ofNullable(loc));
                }
                if (member instanceof MethodTree mt
                        && mt.getName() != null
                        && mt.getName().contentEquals(name)
                        && !mt.getName().contentEquals("<init>")) {
                    return new SymbolIdentity(
                            encTypeName.get(), name, true, null, name, Optional.empty());
                }
            }
            break;
        }
        return null;
    }

    private Optional<String> resolveEnclosingTypeName(TreePath path, ParseTask parse) {
        return enclosingTypeNameFromParse(parse, path);
    }

    private Optional<String> enclosingTypeNameFromParse(ParseTask parse, TreePath path) {
        var classes = new ArrayList<String>();
        for (var cursor = path; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree classTree) {
                classes.add(classTree.getSimpleName().toString());
            }
        }
        if (classes.isEmpty()) {
            return Optional.empty();
        }
        java.util.Collections.reverse(classes);
        var packageName =
                parse.root().getPackageName() == null
                        ? ""
                        : parse.root().getPackageName().toString();
        var qualified =
                packageName.isEmpty()
                        ? String.join(".", classes)
                        : packageName + "." + String.join(".", classes);
        return Optional.of(qualified);
    }

    private long startOffset(ParseTask parse, TreePath path) {
        return Trees.instance(parse.task())
                .getSourcePositions()
                .getStartPosition(parse.root(), path.getLeaf());
    }

    private String selectedName(TreePath path) {
        var leaf = path.getLeaf();
        if (leaf instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        if (leaf instanceof MemberSelectTree ms) {
            return ms.getIdentifier().toString();
        }
        if (leaf instanceof MemberReferenceTree mr) {
            return mr.getName().toString();
        }
        if (leaf instanceof MethodTree mt) {
            return mt.getName() != null ? mt.getName().toString() : null;
        }
        if (leaf instanceof NewClassTree nc) {
            return TypeNames.simpleName(nc.getIdentifier().toString());
        }
        return null;
    }

    private String tryResolveExpressionType(
            ParseTask parse,
            TreePath path,
            ExpressionTree expression,
            ParseTypeResolver types) {
        var resolved = types.resolveExpression(expression);
        if (resolved.isPresent()) {
            return resolved.get().qualifiedType();
        }
        if (expression instanceof IdentifierTree id) {
            var typeResolved =
                    typeIndexRouter.resolveType(
                            id.getName().toString(), parse.root());
            if (typeResolved.isPresent()) {
                return typeResolved.get().qualifiedName;
            }
        }
        if (expression instanceof MemberSelectTree ms) {
            var name = ms.getIdentifier().toString();
            var typeResolved = typeIndexRouter.resolveType(name, parse.root());
            if (typeResolved.isPresent()) {
                return typeResolved.get().qualifiedName;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ReferenceMatcher {
        boolean matches(ParseTask parse, TreePath path);
    }
}
