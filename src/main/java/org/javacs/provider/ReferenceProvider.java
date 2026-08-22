package org.javacs.provider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.LombokAnnotations;
import org.javacs.lsp.Location;
import org.javacs.navigation.FindLombokReferences;
import org.javacs.navigation.FindReferences;
import org.javacs.navigation.NavigationHelper;

public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final Function<Path, CompilerProvider> compilerForFile;
    private final BiPredicate<Path, Path> candidateAllowed;
    private final java.util.function.Consumer<Path[]> batchResolver;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    private static final Logger LOG = Logger.getLogger("main");

    public ReferenceProvider(CompilerProvider compiler, Path file, int line, int column) {
        this(compiler, file, line, column, __ -> compiler, (__, ___) -> true, __ -> {});
    }

    public ReferenceProvider(
            CompilerProvider compiler,
            Path file,
            int line,
            int column,
            Function<Path, CompilerProvider> compilerForFile,
            BiPredicate<Path, Path> candidateAllowed) {
        this(compiler, file, line, column, compilerForFile, candidateAllowed, __ -> {});
    }

    public ReferenceProvider(
            CompilerProvider compiler,
            Path file,
            int line,
            int column,
            Function<Path, CompilerProvider> compilerForFile,
            BiPredicate<Path, Path> candidateAllowed,
            java.util.function.Consumer<Path[]> batchResolver) {
        this.compiler = compiler;
        this.compilerForFile = compilerForFile;
        this.candidateAllowed = candidateAllowed;
        this.batchResolver = batchResolver;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        var start = System.currentTimeMillis();
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) {
                LOG.fine("[ref] target_unresolved");
                return NOT_SUPPORTED;
            }
            if (NavigationHelper.isMember(element)) {
                var parentClass = (TypeElement) element.getEnclosingElement();
                var className = parentClass.getQualifiedName().toString();
                var memberName = element.getSimpleName().toString();
                // Package-private members can only be referenced within the same package
                var isPackagePrivate = !element.getModifiers().contains(Modifier.PUBLIC)
                        && !element.getModifiers().contains(Modifier.PROTECTED)
                        && !element.getModifiers().contains(Modifier.PRIVATE);
                var declarationPath = task.trees.getPath(parentClass);
                var declaration = declarationPath == null
                        ? compiler.findTypeDeclaration(className)
                        : Path.of(declarationPath.getCompilationUnit().getSourceFile().toUri());
                LOG.fine(String.format("[ref] isMember kind=%s name=%s in=%s", element.getKind(), memberName, className));
                if (memberName.equals("<init>")) {
                    memberName = parentClass.getSimpleName().toString();
                }
                // Lombok gate first — private fields with Lombok annotations generate public accessors
                if (compiler.lombokPresentOnClasspath()) {
                    LOG.fine("[ref] lombokOnClasspath=true");
                    var names = lombokSearchNames(element, memberName, task);
                    if (!names.isEmpty()) {
                        task.close();
                        return findLombokReferences(className, names);
                    }
                    LOG.fine("[ref] names empty, falling back to findMemberReferences");
                }
                // Private members (non-Lombok) can only be referenced within the same file
                if (element.getModifiers().contains(Modifier.PRIVATE)) {
                    LOG.fine(String.format("[ref] private_member kind=%s name=%s — file-only scan", element.getKind(), memberName));
                    return findReferences(task);
                }
                task.close();
                return findMemberReferences(className, memberName, declaration, isPackagePrivate);
            }
            if (NavigationHelper.isLocal(element)) {
                return findReferences(task);
            }
            if (NavigationHelper.isType(element)) {
                var type = (TypeElement) element;
                var className = type.getQualifiedName().toString();
                task.close();
                return findTypeReferences(className);
            }
            LOG.fine(String.format(
                    "[ref] unsupported_target kind=%s name=%s",
                    element.getKind(), element.getSimpleName()));
            return NOT_SUPPORTED;
        } finally {
            LOG.fine(String.format(
                    "[perf] references file=%s line=%d column=%d total=%dms",
                    file.getFileName(), line, column, System.currentTimeMillis() - start));
        }
    }

    private List<Location> findTypeReferences(String className) {
        var files = compiler.findTypeReferences(className);
        LOG.fine(String.format("[ref] type_scan owner=%s candidates=%d", className, files.length));
        if (files.length == 0) return List.of();
        return findReferences(files, compiler.findTypeDeclaration(className));
    }

    private List<Location> findMemberReferences(String className, String memberName, Path declaration, boolean packagePrivate) {
        var files = compiler.findMemberReferences(className, memberName);
        if (packagePrivate && declaration != null) {
            var declarationPackage = FileStore.packageName(declaration);
            files = java.util.Arrays.stream(files)
                    .filter(f -> declarationPackage.equals(FileStore.packageName(f)))
                    .toArray(Path[]::new);
            LOG.fine(String.format("[ref] package_private_filter owner=%s name=%s candidates=%d", className, memberName, files.length));
        } else {
            LOG.fine(String.format("[ref] member_scan owner=%s name=%s candidates=%d", className, memberName, files.length));
        }
        if (files.length == 0) return List.of();
        return findReferences(files, declaration);
    }

    private List<Location> findReferences(Path[] files, Path declaration) {
        // Batch pre-resolve all modules these candidate files belong to.
        // For multi-module Gradle/Maven: resolves all needed modules in fewer calls.
        // For single-module: no-op.
        batchResolver.accept(files);
        LOG.fine(String.format("[ref] grouping candidates=%d declaration=%s",
                files.length, declaration == null ? "null" : declaration.getFileName()));
        var groups = new LinkedHashMap<CompilerProvider, LinkedHashSet<Path>>();
        var skippedModules = new HashSet<String>();
        for (var candidate : files) {
            if (!candidateAllowed.test(declaration, candidate)) continue;
            CompilerProvider candidateCompiler;
            try {
                candidateCompiler = compilerForFile.apply(candidate);
            } catch (RuntimeException e) {
                var moduleName = candidate.toString();
                // Extract short module path for dedup
                var moduleKey = e.getMessage() != null ? e.getMessage() : moduleName;
                if (skippedModules.add(moduleKey)) {
                    LOG.warning(String.format(
                            "[ref] skip_candidate file=%s reason=%s",
                            candidate.getFileName(), e.getMessage()));
                }
                continue;
            }
            groups.computeIfAbsent(candidateCompiler, __ -> new LinkedHashSet<>())
                    .add(candidate);
        }
        if (!skippedModules.isEmpty()) {
            LOG.info(String.format("[ref] skipped_modules=%d groups=%d", skippedModules.size(), groups.size()));
        }
        var locations = new LinkedHashMap<String, Location>();
        for (var entry : groups.entrySet()) {
            entry.getValue().add(file);
            try (var task = entry.getKey().compileFresh(entry.getValue().toArray(Path[]::new))) {
                for (var location : findReferences(task)) {
                    locations.put(location.uri + ":" + location.range, location);
                }
            }
        }
        LOG.fine(String.format("[ref] scan_complete groups=%d total_locations=%d", groups.size(), locations.size()));
        return new ArrayList<>(locations.values());
    }

    private List<Location> findReferences(CompileTask task) {
        var element = NavigationHelper.findElement(task, file, line, column);
        var paths = new ArrayList<TreePath>();
        for (var root : task.roots) {
            new FindReferences(task, element).scan(root, paths);
        }
        var locations = new ArrayList<Location>();
        for (var p : paths) {
            locations.add(FindHelper.location(task, p));
        }
        var errors = task.diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
        LOG.fine(String.format(
                "[ref] scan roots=%d compiler_errors=%d matches=%d",
                task.roots.size(), errors, locations.size()));
        return locations;
    }

    private Set<String> lombokSearchNames(Element element, String memberName, CompileTask task) {
        var parent = element.getEnclosingElement();
        LOG.fine(String.format("[ref] lombokSearchNames element.kind=%s memberName=%s", element.getKind(), memberName));
        if (!(parent instanceof TypeElement parentType)) {
            return Set.of();
        }
        if (element.getKind() == ElementKind.METHOD && task.trees.getTree(element) instanceof MethodTree) {
            return Set.of();
        }
        if (!(task.trees.getTree(parentType) instanceof ClassTree declaration)) {
            return Set.of();
        }
        var fieldName = element.getKind() == ElementKind.FIELD
                ? memberName
                : LombokAnnotations.accessorFieldName(memberName).orElse(null);
        if (fieldName == null) {
            return Set.of();
        }

        VariableTree field = null;
        for (var member : declaration.getMembers()) {
            if (member instanceof VariableTree variable
                    && variable.getName().contentEquals(fieldName)) {
                field = variable;
                break;
            }
        }
        if (field == null) {
            return Set.of();
        }
        var accessors = LombokAnnotations.accessorInfo(
                declaration.getModifiers(), field.getModifiers(), fieldName, field.getType().toString());
        if (accessors.isEmpty()) {
            return Set.of();
        }

        var names = new LinkedHashSet<String>();
        names.add(fieldName);
        if (accessors.get().hasGetter()) {
            var getterName = accessors.get().getterName();
            var declared = declaration.getMembers().stream()
                    .anyMatch(member -> member instanceof MethodTree method
                            && method.getName().contentEquals(getterName)
                            && method.getParameters().isEmpty());
            if (!declared) names.add(getterName);
        }
        if (accessors.get().hasSetter()) {
            var setterName = accessors.get().setterName();
            var declared = declaration.getMembers().stream()
                    .anyMatch(member -> member instanceof MethodTree method
                            && method.getName().contentEquals(setterName)
                            && method.getParameters().size() == 1);
            if (!declared) names.add(setterName);
        }
        return names;
    }

    private List<Location> findLombokReferences(String className, Set<String> names) {
        var start = System.currentTimeMillis();
        var files = new LinkedHashSet<Path>();
        for (var name : names) {
            for (var f : compiler.findMemberReferences(className, name)) {
                files.add(f);
            }
        }
        if (files.isEmpty()) {
            LOG.fine(String.format(
                    "[ref] lombok_scan owner=%s names=%s candidates=0 roots=0 compiler_errors=0 matches=0 total=%dms",
                    className, names, System.currentTimeMillis() - start));
            return List.of();
        }
        var groups = new LinkedHashMap<CompilerProvider, LinkedHashSet<Path>>();
        var declaration = compiler.findTypeDeclaration(className);
        for (var candidate : files) {
            if (!candidateAllowed.test(declaration, candidate)) continue;
            var candidateCompiler = compilerForFile.apply(candidate);
            if (!candidateAllowed.test(declaration, candidate)) continue;
            groups.computeIfAbsent(candidateCompiler, __ -> new LinkedHashSet<>())
                    .add(candidate);
        }
        var locations = new LinkedHashMap<String, Location>();
        var roots = 0;
        long errors = 0;
        for (var entry : groups.entrySet()) {
            try (var task = entry.getKey().compileFresh(entry.getValue().toArray(Path[]::new))) {
                var paths = new ArrayList<TreePath>();
                for (var root : task.roots) {
                    new FindLombokReferences(task, names, className).scan(root, paths);
                }
                for (var path : paths) {
                    var location = FindHelper.location(task, path);
                    locations.put(location.uri + ":" + location.range, location);
                }
                roots += task.roots.size();
                errors += task.diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
            }
        }
        LOG.fine(String.format(
                "[ref] lombok_scan owner=%s names=%s candidates=%d roots=%d compiler_errors=%d matches=%d total=%dms",
                className,
                names,
                files.size(),
                roots,
                errors,
                locations.size(),
                System.currentTimeMillis() - start));
        return new ArrayList<>(locations.values());
    }
}
