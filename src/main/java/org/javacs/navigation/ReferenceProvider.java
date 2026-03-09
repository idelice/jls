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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
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

    public static final List<Location> NOT_SUPPORTED = List.of();
    private static final Logger LOG = Logger.getLogger("main");

    public ReferenceProvider(CompilerProvider compiler, Path file, int line, int column) {
        this(compiler, CompositeTypeIndex.EMPTY, file, line, column);
    }

    public ReferenceProvider(CompilerProvider compiler, CompositeTypeIndex completionIndex, Path file, int line, int column) {
        this.compiler = compiler;
        this.completionIndex = completionIndex == null ? CompositeTypeIndex.EMPTY : completionIndex;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        var definitions = new DefinitionProvider(compiler, completionIndex, file, line, column);
        var target = definitions.resolveSymbol();
        if (!isSupported(target)) {
            return NOT_SUPPORTED;
        }
        if (target.qualifiedType() == null) {
            LOG.info(String.format("[perf] references_request mode=parse_index kind=local file=%s", file));
            return findLocalReferences(definitions, target);
        }
        if (target.memberName() == null) {
            LOG.info(
                    String.format(
                            "[perf] references_request mode=parse_index kind=type owner=%s",
                            target.qualifiedType()));
            return findTypeReferences(definitions, target);
        }
        if (!target.method()) {
            LOG.info(
                    String.format(
                            "[perf] references_request mode=parse_index kind=field owner=%s member=%s",
                            target.qualifiedType(), target.memberName()));
            return findFieldReferencesScoped(definitions, target);
        }
        LOG.info(
                String.format(
                        "[perf] references_request mode=parse_index kind=member owner=%s member=%s",
                        target.qualifiedType(), target.memberName()));
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
        return scan(
                files,
                names,
                target,
                (parse, path) -> matchesMemberReference(definitions, parse, path, target, targetParameterTypes));
    }

    private List<Location> findFieldReferencesScoped(
            DefinitionProvider definitions, DefinitionProvider.ResolvedSymbol target) {
        var files = includeDeclarationFile(target.qualifiedType(), compiler.findTypeReferences(target.qualifiedType()));
        var names = new java.util.LinkedHashSet<String>();
        names.add(target.memberName());
        names.addAll(accessorNames(target.memberName()));
        return scan(files, names, target, (parse, path) -> matchesFieldReference(definitions, parse, path, target));
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
                    if (location == null || isDeclarationLocation(location, target)) {
                        return;
                    }
                    dedup.putIfAbsent(key(location), location);
                }
            }.scan(parse.root, null);
        }
        LOG.info(String.format("[perf] references_result_count=%d", dedup.size()));
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
            List<String> targetParameterTypes) {
        var resolved = resolveOccurrence(definitions, parse, path);
        if (isConstructorTarget(target)
                && Objects.equals(target.qualifiedType(), resolved.qualifiedType())
                && !resolved.method()
                && isConstructorUse(path)
                && signatureMatches(targetParameterTypes, occurrenceParameterTypes(parse, path))) {
            return true;
        }
        return resolved.method()
                && Objects.equals(target.qualifiedType(), resolved.qualifiedType())
                && Objects.equals(target.memberName(), resolved.memberName())
                && signatureMatches(targetParameterTypes, occurrenceParameterTypes(parse, path));
    }

    private boolean matchesFieldReference(
            DefinitionProvider definitions, ParseTask parse, TreePath path, DefinitionProvider.ResolvedSymbol target) {
        var resolved = resolveOccurrence(definitions, parse, path);
        if (!Objects.equals(target.qualifiedType(), resolved.qualifiedType())) {
            return false;
        }
        if (!resolved.method() && Objects.equals(target.memberName(), resolved.memberName())) {
            return true;
        }
        return resolved.method() && accessorNames(target.memberName()).contains(resolved.memberName());
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
        var cursor = Trees.instance(parse.task).getSourcePositions().getStartPosition(parse.root, path.getLeaf());
        if (cursor < 0) {
            cursor = parse.root.getLineMap().getPosition(1, 1);
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
        var cursor = parse.root.getLineMap().getPosition(line, column);
        var path = new org.javacs.FindNameAt(parse).scan(parse.root, cursor);
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
        var cursor = Trees.instance(parse.task).getSourcePositions().getStartPosition(parse.root, path.getLeaf()) + 1;
        var resolver = new ParseTypeResolver(parse, completionIndex, compiler, cursor);
        var result = new ArrayList<String>(method.getParameters().size());
        for (VariableTree parameter : method.getParameters()) {
            if (parameter.getType() == null) {
                return List.of();
            }
            var resolved = resolver.resolveTypeTree(parameter.getType(), false);
            result.add(canonicalType(resolved.map(type -> type.qualifiedType).orElse(parameter.getType().toString())));
        }
        return result;
    }

    private List<String> argumentTypes(ParseTask parse, TreePath path, List<? extends ExpressionTree> arguments) {
        var cursor = Trees.instance(parse.task).getSourcePositions().getStartPosition(parse.root, path.getLeaf()) + 1;
        var resolver = new ParseTypeResolver(parse, completionIndex, compiler, cursor);
        var result = new ArrayList<String>(arguments.size());
        for (var argument : arguments) {
            var resolved = resolver.resolveExpression(argument);
            result.add(canonicalType(resolved.map(type -> type.qualifiedType).orElse("")));
        }
        return result;
    }

    private boolean signatureMatches(List<String> targetParameterTypes, List<String> occurrenceParameterTypes) {
        if (targetParameterTypes.isEmpty() || occurrenceParameterTypes.isEmpty()) {
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

    private Path[] includeDeclarationFile(String qualifiedType, Path[] files) {
        var classFile = compiler.findTypeDeclaration(qualifiedType);
        if (classFile == null || classFile.equals(CompilerProvider.NOT_FOUND)) {
            return files;
        }
        var combined = new ArrayList<Path>();
        for (var f : files) {
            combined.add(f);
        }
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
