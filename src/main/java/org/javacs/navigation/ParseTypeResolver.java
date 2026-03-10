package org.javacs.navigation;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import org.javacs.CompilerProvider;
import org.javacs.ParseTask;
import org.javacs.completion.CompositeTypeIndex;
import org.javacs.completion.TypeMemberIndex;

final class ParseTypeResolver {
    static final class TypeResolution {
        final String qualifiedType;
        final boolean staticContext;
        final boolean arrayType;

        TypeResolution(String qualifiedType, boolean staticContext, boolean arrayType) {
            this.qualifiedType = qualifiedType;
            this.staticContext = staticContext;
            this.arrayType = arrayType;
        }
    }

    private static final int MAX_RESOLVE_DEPTH = 24;

    private final CompilationUnitTree root;
    private final SourcePositions positions;
    private final CompilerProvider compiler;
    private final CompositeTypeIndex index;
    private final long cursor;
    private TypeResolution thisType;
    private TypeResolution superType;

    ParseTypeResolver(ParseTask parseTask, CompositeTypeIndex index, CompilerProvider compiler, long cursor) {
        this.root = parseTask.root;
        this.positions = Trees.instance(parseTask.task).getSourcePositions();
        this.compiler = compiler;
        this.index = index == null ? CompositeTypeIndex.EMPTY : index;
        this.cursor = cursor;
    }

    Optional<TypeResolution> resolve(Tree expression, String fallbackIdentifier) {
        if (expression != null) {
            var direct = resolveExpression(expression, 0);
            if (direct.isPresent()) {
                return direct;
            }
        }
        if (fallbackIdentifier == null || fallbackIdentifier.isBlank()) {
            return Optional.empty();
        }
        return resolveIdentifier(fallbackIdentifier, 0);
    }

    Optional<TypeResolution> resolveExpression(Tree expression) {
        return resolveExpression(expression, 0);
    }

    Optional<TypeResolution> resolveIdentifier(String identifier) {
        return resolveIdentifier(identifier, 0);
    }

    Optional<TreePath> resolveVisibleDeclaration(String targetName) {
        var best = new CandidatePath(null, -1, -1);
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void p) {
                if (!containsCursor(classTree)) return null;
                for (var member : classTree.getMembers()) {
                    if (member instanceof VariableTree) {
                        scan(member, p);
                    } else if (containsCursor(member)) {
                        scan(member, p);
                    }
                }
                return null;
            }

            @Override
            public Void visitMethod(MethodTree methodTree, Void p) {
                if (!containsCursor(methodTree)) return null;
                for (var parameter : methodTree.getParameters()) {
                    consider(parameter, getCurrentPath());
                }
                if (methodTree.getBody() != null) {
                    scan(methodTree.getBody(), p);
                }
                return null;
            }

            @Override
            public Void visitBlock(BlockTree blockTree, Void p) {
                if (!containsCursor(blockTree)) return null;
                for (var statement : blockTree.getStatements()) {
                    var start = startOf(statement);
                    if (start >= 0 && start >= cursor) {
                        break;
                    }
                    scan(statement, p);
                }
                return null;
            }

            @Override
            public Void visitVariable(VariableTree variableTree, Void p) {
                consider(variableTree, getCurrentPath());
                return null;
            }

            private void consider(VariableTree variableTree, TreePath path) {
                if (!variableTree.getName().contentEquals(targetName)) return;
                var start = startOf(variableTree);
                if (start < 0 || start >= cursor) return;
                var candidate = new CandidatePath(path, depth(path), start);
                if (isBetter(candidate, best)) {
                    best.path = candidate.path;
                    best.depth = candidate.depth;
                    best.start = candidate.start;
                }
            }
        }.scan(root, null);

        return Optional.ofNullable(best.path);
    }

    Optional<String> currentEnclosingTypeName() {
        return resolveThisType().map(type -> type.qualifiedType);
    }

    private Optional<TypeResolution> resolveExpression(Tree expression, int depth) {
        if (expression == null || depth > MAX_RESOLVE_DEPTH) {
            return Optional.empty();
        }
        if (expression instanceof ParenthesizedTree parenthesized) {
            return resolveExpression(parenthesized.getExpression(), depth + 1);
        }
        if (expression instanceof IdentifierTree identifier) {
            return resolveIdentifier(identifier.getName().toString(), depth + 1);
        }
        if (expression instanceof NewClassTree newClassTree) {
            return resolveTypeTree(newClassTree.getIdentifier(), false);
        }
        if (expression instanceof NewArrayTree newArrayTree) {
            if (newArrayTree.getType() == null) {
                return Optional.empty();
            }
            var component = resolveTypeTree(newArrayTree.getType(), false);
            if (component.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new TypeResolution(component.get().qualifiedType, false, true));
        }
        if (expression instanceof MethodInvocationTree invocationTree) {
            return resolveMethodInvocation(invocationTree, depth + 1);
        }
        if (expression instanceof MemberSelectTree memberSelectTree) {
            return resolveMemberSelect(memberSelectTree, depth + 1);
        }
        if (expression instanceof TypeCastTree castTree) {
            return resolveTypeTree(castTree.getType(), false);
        }
        if (expression instanceof ArrayAccessTree arrayAccessTree) {
            var array = resolveExpression(arrayAccessTree.getExpression(), depth + 1);
            if (array.isEmpty()) return Optional.empty();
            var type = array.get();
            if (!type.arrayType || type.qualifiedType == null || type.qualifiedType.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TypeResolution(type.qualifiedType, false, false));
        }
        if (expression instanceof LiteralTree literal) {
            return literalType(literal);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMethodInvocation(MethodInvocationTree invocationTree, int depth) {
        var select = invocationTree.getMethodSelect();
        if (select instanceof IdentifierTree identifier) {
            var current = resolveThisType();
            if (current.isEmpty()) return Optional.empty();
            var method = index.member(current.get().qualifiedType, identifier.getName().toString(), false);
            if (method.isEmpty()) return Optional.empty();
            return returnTypeOf(method.get());
        }
        if (select instanceof MemberSelectTree memberSelectTree) {
            var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
            if (receiver.isEmpty()) return Optional.empty();
            var method =
                    index.member(
                            receiver.get().qualifiedType,
                            memberSelectTree.getIdentifier().toString(),
                            receiver.get().staticContext);
            if (method.isEmpty()) return Optional.empty();
            return returnTypeOf(method.get());
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMemberSelect(MemberSelectTree memberSelectTree, int depth) {
        var receiver = resolveExpression(memberSelectTree.getExpression(), depth + 1);
        if (receiver.isEmpty()) {
            return resolveTypeTree(memberSelectTree, true);
        }
        if (receiver.get().arrayType && "length".equals(memberSelectTree.getIdentifier().toString())) {
            return Optional.of(new TypeResolution("int", false, false));
        }
        if (receiver.get().staticContext
                && "class".equals(memberSelectTree.getIdentifier().toString())) {
            return Optional.of(new TypeResolution("java.lang.Class", false, false));
        }
        var member =
                index.member(
                        receiver.get().qualifiedType,
                        memberSelectTree.getIdentifier().toString(),
                        receiver.get().staticContext);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        return returnTypeOf(member.get());
    }

    private Optional<TypeResolution> resolveIdentifier(String identifier, int depth) {
        if ("this".equals(identifier)) {
            return resolveThisType();
        }
        if ("super".equals(identifier)) {
            return resolveSuperType();
        }
        var variable = resolveVisibleVariable(identifier, depth + 1);
        if (variable.isPresent()) {
            return variable;
        }
        var inheritedField = resolveInheritedFieldMember(identifier);
        if (inheritedField.isPresent()) {
            return returnTypeOf(inheritedField.get());
        }
        var nested = resolveNestedTypeInEnclosingScopes(identifier);
        if (nested.isPresent()) {
            return Optional.of(new TypeResolution(nested.get(), true, false));
        }
        var type = index.resolveTypeName(identifier, root);
        if (type.isPresent()) {
            return Optional.of(new TypeResolution(type.get(), true, false));
        }
        var fallback = resolveTypeNameFallback(identifier);
        if (fallback.isPresent()) {
            return Optional.of(new TypeResolution(fallback.get(), true, false));
        }
        return Optional.empty();
    }

    private Optional<String> resolveNestedTypeInEnclosingScopes(String simpleName) {
        return resolveNestedTypeFallback(simpleName);
    }

    private TreePath parentClassPath(TreePath from) {
        for (var cursorPath = from; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
            if (cursorPath.getLeaf() instanceof ClassTree) {
                return cursorPath;
            }
        }
        return null;
    }

    private Optional<TypeResolution> resolveVisibleVariable(String targetName, int depth) {
        final CandidateType[] best = new CandidateType[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void p) {
                if (!containsCursor(classTree)) return null;
                for (var member : classTree.getMembers()) {
                    if (member instanceof VariableTree) {
                        scan(member, p);
                    } else if (containsCursor(member)) {
                        scan(member, p);
                    }
                }
                return null;
            }

            @Override
            public Void visitMethod(MethodTree methodTree, Void p) {
                if (!containsCursor(methodTree)) return null;
                for (var parameter : methodTree.getParameters()) {
                    consider(parameter, getCurrentPath(), depth + 1);
                }
                if (methodTree.getBody() != null) {
                    scan(methodTree.getBody(), p);
                }
                return null;
            }

            @Override
            public Void visitBlock(BlockTree blockTree, Void p) {
                if (!containsCursor(blockTree)) return null;
                for (var statement : blockTree.getStatements()) {
                    var start = startOf(statement);
                    if (start >= 0 && start >= cursor) {
                        break;
                    }
                    scan(statement, p);
                }
                return null;
            }

            @Override
            public Void visitVariable(VariableTree variableTree, Void p) {
                consider(variableTree, getCurrentPath(), depth + 1);
                return null;
            }

            private void consider(VariableTree variableTree, TreePath path, int nextDepth) {
                if (!variableTree.getName().contentEquals(targetName)) return;
                var start = startOf(variableTree);
                if (start < 0 || start >= cursor) return;
                var resolved = resolveVariableType(variableTree, nextDepth);
                if (resolved.isEmpty()) return;
                var candidate = new CandidateType(resolved.get(), depth(path), start);
                if (best[0] == null
                        || candidate.depth > best[0].depth
                        || (candidate.depth == best[0].depth && candidate.start > best[0].start)) {
                    best[0] = candidate;
                }
            }
        }.scan(root, null);

        if (best[0] == null) return Optional.empty();
        return Optional.of(best[0].resolution);
    }

    Optional<TypeMemberIndex.Member> resolveInheritedFieldMember(String identifier) {
        var owner = resolveThisType();
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        var member = index.member(owner.get().qualifiedType, identifier, false);
        if (member.isEmpty() || member.get().kind != org.javacs.lsp.CompletionItemKind.Field) {
            return Optional.empty();
        }
        return member;
    }

    private Optional<TypeResolution> resolveVariableType(VariableTree variableTree, int depth) {
        if (variableTree.getType() != null && !"var".equals(variableTree.getType().toString())) {
            return resolveTypeTree(variableTree.getType(), false);
        }
        if (variableTree.getInitializer() != null) {
            return resolveExpression(variableTree.getInitializer(), depth + 1);
        }
        return Optional.empty();
    }

    Optional<TypeResolution> resolveTypeTree(Tree tree, boolean staticContext) {
        if (tree == null) return Optional.empty();
        if (tree instanceof AnnotatedTypeTree annotatedTypeTree) {
            return resolveTypeTree(annotatedTypeTree.getUnderlyingType(), staticContext);
        }
        if (tree instanceof ParameterizedTypeTree parameterizedTypeTree) {
            return resolveTypeTree(parameterizedTypeTree.getType(), staticContext);
        }
        if (tree instanceof ArrayTypeTree arrayTypeTree) {
            var component = resolveTypeTree(arrayTypeTree.getType(), staticContext);
            if (component.isEmpty()) return Optional.empty();
            return Optional.of(new TypeResolution(component.get().qualifiedType, staticContext, true));
        }
        var typeName = tree.toString();
        var resolved = resolveTypeName(typeName);
        return resolved.map(type -> new TypeResolution(type, staticContext, false));
    }

    private Optional<TypeResolution> resolveThisType() {
        if (thisType != null) {
            return Optional.of(thisType);
        }
        var classPath = enclosingClassPath();
        if (classPath == null) return Optional.empty();
        var qualified = qualifiedClassName(classPath);
        thisType = new TypeResolution(qualified, false, false);
        return Optional.of(thisType);
    }

    private Optional<TypeResolution> resolveSuperType() {
        if (superType != null) {
            return Optional.of(superType);
        }
        var classPath = enclosingClassPath();
        if (classPath == null) return Optional.empty();
        var classTree = (ClassTree) classPath.getLeaf();
        if (classTree.getExtendsClause() == null) {
            superType = new TypeResolution("java.lang.Object", false, false);
            return Optional.of(superType);
        }
        var resolved = resolveTypeTree(classTree.getExtendsClause(), false);
        if (resolved.isPresent()) {
            superType = resolved.get();
            return resolved;
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> returnTypeOf(TypeMemberIndex.Member member) {
        if (member.returnType == null || member.returnType.isBlank()) {
            return Optional.empty();
        }
        var type = member.returnType;
        var isArray = type.endsWith("[]");
        if (isArray) {
            type = type.substring(0, type.length() - 2);
        }
        var resolved = resolveTypeName(type);
        return resolved.map(next -> new TypeResolution(next, false, isArray));
    }

    private Optional<String> resolveTypeName(String typeName) {
        var indexed = index.resolveTypeName(typeName, root);
        if (indexed.isPresent()) {
            return indexed;
        }
        return resolveTypeNameFallback(typeName);
    }

    private Optional<String> resolveTypeNameFallback(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var raw = typeName.trim();
        while (raw.endsWith("[]")) {
            raw = raw.substring(0, raw.length() - 2);
        }
        var genericStart = raw.indexOf('<');
        if (genericStart >= 0) {
            raw = raw.substring(0, genericStart);
        }
        if (raw.startsWith("? extends ")) {
            raw = raw.substring("? extends ".length()).trim();
        } else if (raw.startsWith("? super ")) {
            raw = raw.substring("? super ".length()).trim();
        } else if ("?".equals(raw)) {
            return Optional.empty();
        }
        if (raw.isBlank()) {
            return Optional.empty();
        }
        if (TypeMemberIndex.isPrimitiveTypeName(raw)) {
            return Optional.of(raw);
        }
        if (raw.contains(".")) {
            if (compiler.findAnywhere(raw).isPresent()) {
                return Optional.of(raw);
            }
        }
        var nested = resolveNestedTypeFallback(raw);
        if (nested.isPresent()) {
            return nested;
        }

        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (!packageName.isBlank()) {
            var candidate = packageName + "." + raw;
            if (compiler.findAnywhere(candidate).isPresent()) {
                return Optional.of(candidate);
            }
        }

        for (var importTree : root.getImports()) {
            if (importTree.isStatic()) continue;
            var imported = importTree.getQualifiedIdentifier().toString();
            if (imported.endsWith("." + raw)) {
                if (compiler.findAnywhere(imported).isPresent()) {
                    return Optional.of(imported);
                }
            }
            if (imported.endsWith(".*")) {
                var candidate = imported.substring(0, imported.length() - 1) + raw;
                if (compiler.findAnywhere(candidate).isPresent()) {
                    return Optional.of(candidate);
                }
            }
        }

        var javaLang = "java.lang." + raw;
        if (compiler.findAnywhere(javaLang).isPresent()) {
            return Optional.of(javaLang);
        }
        return Optional.empty();
    }

    private Optional<String> resolveNestedTypeFallback(String simpleName) {
        for (var classPath = enclosingClassPath();
                classPath != null;
                classPath = parentClassPath(classPath.getParentPath())) {
            var owner = qualifiedClassName(classPath);
            var candidate = owner + "." + simpleName;
            if (index.containsType(candidate) || compiler.findAnywhere(candidate).isPresent()) {
                return Optional.of(candidate);
            }
            var classTree = (ClassTree) classPath.getLeaf();
            for (var member : classTree.getMembers()) {
                if (!(member instanceof ClassTree nested)) continue;
                if (!nested.getSimpleName().contentEquals(simpleName)) continue;
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> literalType(LiteralTree literal) {
        var value = literal.getValue();
        if (value == null) return Optional.empty();
        if (value instanceof String) return Optional.of(new TypeResolution("java.lang.String", false, false));
        if (value instanceof Integer) return Optional.of(new TypeResolution("java.lang.Integer", false, false));
        if (value instanceof Long) return Optional.of(new TypeResolution("java.lang.Long", false, false));
        if (value instanceof Float) return Optional.of(new TypeResolution("java.lang.Float", false, false));
        if (value instanceof Double) return Optional.of(new TypeResolution("java.lang.Double", false, false));
        if (value instanceof Boolean) return Optional.of(new TypeResolution("java.lang.Boolean", false, false));
        if (value instanceof Character) return Optional.of(new TypeResolution("java.lang.Character", false, false));
        return Optional.empty();
    }

    private TreePath enclosingClassPath() {
        final TreePath[] best = new TreePath[1];
        final int[] bestDepth = {-1};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void p) {
                if (!containsCursor(classTree)) return null;
                var pathDepth = depth(getCurrentPath());
                if (pathDepth > bestDepth[0]) {
                    bestDepth[0] = pathDepth;
                    best[0] = getCurrentPath();
                }
                return super.visitClass(classTree, p);
            }
        }.scan(root, null);
        return best[0];
    }

    private String qualifiedClassName(TreePath classPath) {
        var classes = new ArrayList<String>();
        for (var cursorPath = classPath; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
            if (cursorPath.getLeaf() instanceof ClassTree classTree) {
                classes.add(classTree.getSimpleName().toString());
            }
        }
        Collections.reverse(classes);
        var packageName = root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (packageName.isEmpty()) {
            return String.join(".", classes);
        }
        return packageName + "." + String.join(".", classes);
    }

    private boolean containsCursor(Tree tree) {
        var start = startOf(tree);
        var end = endOf(tree);
        if (start < 0 || end < 0) return false;
        return start <= cursor && cursor <= end;
    }

    private long startOf(Tree tree) {
        return positions.getStartPosition(root, tree);
    }

    private long endOf(Tree tree) {
        return positions.getEndPosition(root, tree);
    }

    private int depth(TreePath path) {
        var value = 0;
        for (var cursorPath = path; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
            value++;
        }
        return value;
    }

    private boolean isBetter(CandidatePath next, CandidatePath existing) {
        return existing.path == null
                || next.depth > existing.depth
                || (next.depth == existing.depth && next.start > existing.start);
    }

    private static final class CandidateType {
        final TypeResolution resolution;
        final int depth;
        final long start;

        CandidateType(TypeResolution resolution, int depth, long start) {
            this.resolution = resolution;
            this.depth = depth;
            this.start = start;
        }
    }

    private static final class CandidatePath {
        TreePath path;
        int depth;
        long start;

        CandidatePath(TreePath path, int depth, long start) {
            this.path = path;
            this.depth = depth;
            this.start = start;
        }
    }
}
