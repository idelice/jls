package org.javacs.provider;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.Location;
import org.javacs.navigation.NavigationHelper;
import org.javacs.resolve.TypeNames;

/** Finds workspace type and method implementations, verified by javac. */
public final class ImplementationProvider {
    private static final Logger LOG = Logger.getLogger("main");

    public static final List<Location> NOT_SUPPORTED = List.of();

    private final CompilerProvider compiler;
    private final Path file;
    private final int line;
    private final int column;
    private final Function<Path, CompilerProvider> compilerForFile;
    private final BiPredicate<Path, Path> candidateAllowed;
    private final Consumer<Path[]> batchResolver;

    public ImplementationProvider(
            CompilerProvider compiler,
            Path file,
            int line,
            int column,
            Function<Path, CompilerProvider> compilerForFile,
            BiPredicate<Path, Path> candidateAllowed,
            Consumer<Path[]> batchResolver) {
        this.compiler = compiler;
        this.file = file;
        this.line = line;
        this.column = column;
        this.compilerForFile = compilerForFile;
        this.candidateAllowed = candidateAllowed;
        this.batchResolver = batchResolver;
    }

    public List<Location> find() {
        Target target;
        try (var task = compiler.compile(file)) {
            target = target(task, NavigationHelper.findElement(task, file, line, column));
        }
        if (target == null) return NOT_SUPPORTED;

        var locations = new LinkedHashMap<String, Location>();
        discoverFromSource(target, locations);
        return new ArrayList<>(locations.values());
    }

    private Target target(CompileTask task, Element element) {
        if (element instanceof TypeElement type) {
            return new Target(
                    type.getQualifiedName().toString(),
                    null,
                    new String[0],
                    declarationPath(task, type));
        }
        if (!(element instanceof ExecutableElement method)
                || method.getKind() != ElementKind.METHOD
                || !(method.getEnclosingElement() instanceof TypeElement owner)) {
            return null;
        }
        return new Target(
                owner.getQualifiedName().toString(),
                method.getSimpleName().toString(),
                FindHelper.erasedParameterTypes(task, method),
                declarationPath(task, owner));
    }

    private Path declarationPath(CompileTask task, TypeElement type) {
        var path = task.trees.getPath(type);
        if (path != null) {
            var source = sourcePath(path.getCompilationUnit().getSourceFile().toUri());
            if (source != null) return source;
        }
        var found = compiler.findTypeDeclaration(type.getQualifiedName().toString());
        return found == CompilerProvider.NOT_FOUND ? null : found;
    }

    private void discoverFromSource(Target target, Map<String, Location> locations) {
        Set<String> pendingTypes = new LinkedHashSet<>();
        var searchedTypes = new HashSet<String>();
        var compiledFiles = new HashSet<Path>();
        pendingTypes.add(target.ownerType());

        while (!pendingTypes.isEmpty()) {
            pendingTypes.removeAll(searchedTypes);
            if (pendingTypes.isEmpty()) return;
            searchedTypes.addAll(pendingTypes);

            var candidates = hierarchyCandidates(target, pendingTypes, compiledFiles);
            pendingTypes = compileCandidates(target, candidates, locations);
        }
    }

    private Set<Path> hierarchyCandidates(
            Target target, Set<String> typeNames, Set<Path> compiledFiles) {
        var textual = compiler.findTypeReferences(typeNames);
        var candidates = new LinkedHashSet<Path>();
        for (var candidate : textual) {
            var source = normalized(candidate);
            if (compiledFiles.contains(source)
                    || !candidateAllowed.test(target.declaration(), source)
                    || !couldDeclareSubtype(source, typeNames)) {
                continue;
            }
            compiledFiles.add(source);
            candidates.add(source);
        }
        return candidates;
    }

    private boolean couldDeclareSubtype(Path source, Set<String> typeNames) {
        try {
            var parsed = compiler.parse(source);
            if (parsed.hasSyntaxErrors()) return true;
            var scanner = new HierarchyReferenceScanner(parsed.root(), typeNames);
            scanner.scan(parsed.root(), null);
            return scanner.found;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private Set<String> compileCandidates(
            Target target, Set<Path> candidates, Map<String, Location> locations) {
        if (candidates.isEmpty()) return Set.of();
        var discovered = new LinkedHashSet<String>();
        for (var group : compilerGroups(candidates).entrySet()) {
            try (var task = group.getKey().compileFresh(group.getValue().toArray(Path[]::new))) {
                discovered.addAll(scanCandidates(task, target, group.getValue(), locations));
            } catch (RuntimeException e) {
                LOG.warning(String.format(
                        "[implementation] skip_group files=%d reason=%s",
                        group.getValue().size(), e.getMessage()));
            }
        }
        return discovered;
    }

    private Map<CompilerProvider, LinkedHashSet<Path>> compilerGroups(Set<Path> candidates) {
        batchResolver.accept(candidates.toArray(Path[]::new));
        var groups = new LinkedHashMap<CompilerProvider, LinkedHashSet<Path>>();
        var skipped = new HashSet<String>();
        for (var candidate : candidates) {
            try {
                var candidateCompiler = compilerForFile.apply(candidate);
                groups.computeIfAbsent(candidateCompiler, __ -> new LinkedHashSet<>()).add(candidate);
            } catch (RuntimeException e) {
                var reason = String.valueOf(e.getMessage());
                if (skipped.add(reason)) {
                    LOG.warning(String.format(
                            "[implementation] skip_candidate file=%s reason=%s",
                            candidate.getFileName(), reason));
                }
            }
        }
        return groups;
    }

    private Set<String> scanCandidates(
            CompileTask task,
            Target target,
            Set<Path> candidates,
            Map<String, Location> locations) {
        var targetType = task.elements.getTypeElement(target.ownerType());
        if (targetType == null) return Set.of();
        var targetMethod = target.methodName() == null
                ? null
                : FindHelper.findMethod(
                        task,
                        target.ownerType(),
                        target.methodName(),
                        target.erasedParameterTypes());
        if (target.methodName() != null && targetMethod == null) return Set.of();

        var discovered = new LinkedHashSet<String>();
        for (var root : task.roots) {
            var source = sourcePath(root.getSourceFile().toUri());
            if (source == null || !candidates.contains(source)) continue;
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree tree, Void unused) {
                    var element = task.trees.getElement(getCurrentPath());
                    if (element instanceof TypeElement candidate
                            && !candidate.equals(targetType)
                            && task.types.isSubtype(
                                    task.types.erasure(candidate.asType()),
                                    task.types.erasure(targetType.asType()))) {
                        var name = candidate.getQualifiedName().toString();
                        if (!name.isBlank()) discovered.add(name);
                        if (targetMethod == null) {
                            var path = candidate.getSimpleName().isEmpty()
                                    ? getCurrentPath().getParentPath()
                                    : getCurrentPath();
                            addLocation(
                                    locations,
                                    FindHelper.location(task, path, candidate.getSimpleName()));
                        } else {
                            addMethodImplementations(task, targetMethod, candidate, locations);
                        }
                    }
                    return super.visitClass(tree, unused);
                }
            }.scan(root, null);
        }
        return discovered;
    }

    private static void addMethodImplementations(
            CompileTask task,
            ExecutableElement target,
            TypeElement candidate,
            Map<String, Location> locations) {
        for (var member : candidate.getEnclosedElements()) {
            if (!(member instanceof ExecutableElement method)
                    || method.getKind() != ElementKind.METHOD
                    || !method.getSimpleName().contentEquals(target.getSimpleName())
                    || !task.elements.overrides(method, target, candidate)) {
                continue;
            }
            var path = task.trees.getPath(method);
            if (path != null) {
                addLocation(locations, FindHelper.location(task, path, method.getSimpleName()));
            }
        }
    }

    private static void addLocation(Map<String, Location> locations, Location location) {
        if (location == null || location.uri == null || location.range == null) return;
        locations.putIfAbsent(location.uri + ":" + location.range, location);
    }

    private static Path sourcePath(URI uri) {
        return uri != null && "file".equals(uri.getScheme()) ? normalized(Path.of(uri)) : null;
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static final class HierarchyReferenceScanner extends TreeScanner<Void, Void> {
        private final CompilationUnitTree root;
        private final Set<String> qualifiedNames;
        private final Set<String> simpleNames;
        private boolean found;

        private HierarchyReferenceScanner(CompilationUnitTree root, Set<String> qualifiedNames) {
            this.root = root;
            this.qualifiedNames = Set.copyOf(qualifiedNames);
            var simpleNames = new HashSet<String>();
            for (var name : qualifiedNames) simpleNames.add(TypeNames.simpleName(name));
            this.simpleNames = Set.copyOf(simpleNames);
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            if (matches(tree.getExtendsClause())) {
                found = true;
                return null;
            }
            for (var type : tree.getImplementsClause()) {
                if (matches(type)) {
                    found = true;
                    return null;
                }
            }
            return super.visitClass(tree, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void unused) {
            if (tree.getClassBody() != null && matches(tree.getIdentifier())) {
                found = true;
                return null;
            }
            return super.visitNewClass(tree, unused);
        }

        private boolean matches(Tree tree) {
            while (tree instanceof AnnotatedTypeTree annotated) tree = annotated.getUnderlyingType();
            while (tree instanceof ParameterizedTypeTree parameterized) tree = parameterized.getType();
            if (tree == null) return false;

            var name = TypeNames.normalize(tree.toString());
            if (qualifiedNames.contains(name)) return true;
            if (name.contains(".")) {
                for (var qualified : qualifiedNames) {
                    if (qualified.endsWith("." + name)) return true;
                }
                return false;
            }
            if (!simpleNames.contains(name)) return false;
            return TypeNames.resolveSimpleName(name, root, qualifiedNames::contains)
                    .map(qualifiedNames::contains)
                    .orElse(true);
        }
    }

    private record Target(
            String ownerType,
            String methodName,
            String[] erasedParameterTypes,
            Path declaration) {}
}
