package org.javacs.provider;

import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;
import org.javacs.FindHelper;
import org.javacs.LombokAnnotations;
import org.javacs.SourceFileObject;
import org.javacs.completion.PruneBodiesWithoutToken;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.lsp.Location;
import org.javacs.navigation.FindLombokReferences;
import org.javacs.navigation.FindReferences;
import org.javacs.navigation.NavigationHelper;
import org.javacs.resolve.TypeNames;

public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final Function<Path, CompilerProvider> compilerForFile;
    private final BiPredicate<Path, Path> candidateAllowed;
    private final Consumer<Path[]> batchResolver;
    private final Path file;
    private final int line, column;
    private final WorkspaceTypeIndex typeIndex;

    public static final List<Location> NOT_SUPPORTED = List.of();

    private static final Logger LOG = Logger.getLogger("main");

    public ReferenceProvider(
            CompilerProvider compiler,
            Path file,
            int line,
            int column,
            Function<Path, CompilerProvider> compilerForFile,
            BiPredicate<Path, Path> candidateAllowed,
            Consumer<Path[]> batchResolver,
            WorkspaceTypeIndex typeIndex) {
        this.compiler = compiler;
        this.compilerForFile = compilerForFile;
        this.candidateAllowed = candidateAllowed;
        this.batchResolver = batchResolver;
        this.typeIndex = typeIndex;
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
            // javac attributes this/super keywords to synthetic fields; they are not declarations.
            if (element.getKind() == ElementKind.FIELD
                    && (element.getSimpleName().contentEquals("this")
                            || element.getSimpleName().contentEquals("super"))) {
                return NOT_SUPPORTED;
            }
            if (NavigationHelper.isMember(element)) {
                var parentClass = (TypeElement) element.getEnclosingElement();
                var className = parentClass.getQualifiedName().toString();
                var memberName = element.getSimpleName().toString();
                var isPackagePrivate = !element.getModifiers().contains(Modifier.PUBLIC)
                        && !element.getModifiers().contains(Modifier.PROTECTED)
                        && !element.getModifiers().contains(Modifier.PRIVATE);
                var declarationPath = task.trees.getPath(parentClass);
                var declaration = declarationPath == null
                        ? compiler.findTypeDeclaration(className)
                        : Path.of(declarationPath.getCompilationUnit().getSourceFile().toUri());
                LOG.fine(String.format("[ref] isMember kind=%s name=%s in=%s", element.getKind(), memberName, className));
                var isConstructor = memberName.equals("<init>");
                if (isConstructor) {
                    memberName = parentClass.getSimpleName().toString();
                }
                var root = task.root(file);
                if (compiler.lombokPresentOnClasspath()
                        && root != null
                        && LombokAnnotations.hasStructuralLombokAnnotation(root)) {
                    var accessor = typeIndex.accessorNames(className, memberName).orElse(null);
                    if (accessor != null) {
                        var lombokClassName = accessor.ownerType();
                        var lombokDeclaration = lombokClassName.equals(className)
                                ? declaration
                                : compiler.findTypeDeclaration(lombokClassName);
                        if (!accessor.names().isEmpty()) {
                            task.close();
                            return findLombokReferences(
                                    lombokClassName, accessor.names(), lombokDeclaration);
                        }
                    }
                }
                // Private non-Lombok: file-only scan (record components are private but have public accessors)
                if (element.getModifiers().contains(Modifier.PRIVATE)
                        && parentClass.getKind() != ElementKind.RECORD) {
                    LOG.fine(String.format("[ref] private_member kind=%s name=%s — file-only scan", element.getKind(), memberName));
                    return findReferences(task);
                }
                // For methods, search supertypes that declare the same name (interface chain)
                var searchClassNames = new LinkedHashSet<String>();
                searchClassNames.add(className);
                if (element instanceof ExecutableElement method) {
                    for (var st : NavigationHelper.findDeclaringSupertypes(task.types, parentClass, memberName)) {
                        searchClassNames.add(st.getQualifiedName().toString());
                    }
                }
                task.close();
                if (parentClass.getKind() == ElementKind.ANNOTATION_TYPE) {
                    return findAnnotationElementReferences(className, declaration);
                }
                return findMemberReferences(
                        searchClassNames, memberName, declaration, isPackagePrivate, isConstructor);
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
            LOG.info(String.format(
                    "[ref] unsupported_target kind=%s name=%s",
                    element.getKind(), element.getSimpleName()));
            return NOT_SUPPORTED;
        } finally {
            LOG.fine(String.format(
                    "[perf] references file=%s line=%d column=%d total=%dms",
                    file.getFileName(), line, column, System.currentTimeMillis() - start));
        }
    }

    private List<Path> tokenCandidates(String name) {
        if (typeIndex == null) return null;
        return typeIndex.filesContainingToken(name);
    }

    private List<Location> findTypeReferences(String className) {
        var simpleName = TypeNames.simpleName(className);
        var postings = tokenCandidates(simpleName);
        var files = compiler.findTypeReferences(className, postings);
        LOG.fine(String.format("[ref] type_scan owner=%s candidates=%d source=%s",
                className, files.length, postings != null ? "token_index" : "text_scan"));
        if (files.length == 0) return List.of();
        return findReferences(files, compiler.findTypeDeclaration(className), simpleName);
    }

    private List<Location> findAnnotationElementReferences(String className, Path declaration) {
        // Shorthand @A("x") has no member token; search by the annotation type name instead.
        var simpleName = TypeNames.simpleName(className);
        var files = compiler.findTypeReferences(className, tokenCandidates(simpleName));
        var candidates = new LinkedHashSet<Path>();
        candidates.addAll(Arrays.asList(files));
        candidates.add(file);
        if (declaration != null && !declaration.equals(CompilerProvider.NOT_FOUND)) {
            candidates.add(declaration);
        }
        return findReferences(candidates.toArray(Path[]::new), declaration, null);
    }

    private List<Location> findMemberReferences(
            Set<String> classNames, String memberName, Path declaration, boolean packagePrivate, boolean constructor) {
        var postings = tokenCandidates(memberName);
        var files = compiler.findMemberReferences(classNames, Set.of(memberName), postings);
        LOG.fine(String.format("[ref] member_scan owner=%s name=%s candidates=%d source=%s",
                classNames, memberName, files.length, postings != null ? "token_index" : "text_scan"));
        if (packagePrivate && declaration != null) {
            var declarationPackage = FileStore.packageName(declaration);
            files = Arrays.stream(files)
                    .filter(f -> declarationPackage.equals(FileStore.packageName(f)))
                    .toArray(Path[]::new);
            LOG.fine(String.format("[ref] package_private_filter owner=%s name=%s candidates=%d", classNames, memberName, files.length));
        }
        // The cursor file and the declaration file must always be scanned. Token postings can be
        // empty when the file never mentions the type name (e.g. a constructor used only by this(...)).
        var candidates = new LinkedHashSet<Path>();
        candidates.addAll(Arrays.asList(files));
        candidates.add(file);
        if (declaration != null && !declaration.equals(CompilerProvider.NOT_FOUND)) {
            candidates.add(declaration);
        }
        files = candidates.toArray(Path[]::new);
        if (files.length == 0) return List.of();
        // Constructor bodies contain the only trace of this(...)/super(...), which the token never
        // mentions, so they must not be pruned.
        return findReferences(files, declaration, constructor ? null : memberName);
    }

    private List<Location> findReferences(Path[] files, Path declaration, String token) {
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
            var candidateCompiler = entry.getKey();
            var sources = new ArrayList<JavaFileObject>();
            for (var candidate : entry.getValue()) {
                if (candidate.equals(file)) continue;
                sources.add(prunedSource(candidateCompiler, candidate, token));
            }
            sources.add(new SourceFileObject(file));
            try (var task = candidateCompiler.compile(sources)) {
                for (var location : findReferences(task)) {
                    locations.put(FindHelper.locationKey(location), location);
                }
            }
        }
        LOG.fine(String.format("[ref] scan_complete groups=%d total_locations=%d", groups.size(), locations.size()));
        return new ArrayList<>(locations.values());
    }

    private JavaFileObject prunedSource(CompilerProvider candidateCompiler, Path candidate, String token) {
        if (token == null || token.isBlank()) return new SourceFileObject(candidate);
        try {
            var parsed = candidateCompiler.parse(candidate);
            var pruned = new PruneBodiesWithoutToken(parsed.task()).scan(parsed.root(), token);
            return new SourceFileObject(candidate, pruned.toString(), Instant.now());
        } catch (RuntimeException e) {
            return new SourceFileObject(candidate);
        }
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

    private List<Location> findLombokReferences(String className, Set<String> names, Path declaration) {
        var start = System.currentTimeMillis();
        var files = new LinkedHashSet<Path>();
        var useIndex = typeIndex != null;
        List<Path> postings = null;
        if (useIndex) {
            postings = new ArrayList<>();
            for (var name : names) {
                var p = typeIndex.filesContainingToken(name);
                if (p == null) {
                    useIndex = false;
                    postings = null;
                    break;
                }
                postings.addAll(p);
            }
        }
        for (var f : compiler.findMemberReferences(Set.of(className), names, postings)) {
            files.add(f);
        }
        if (files.isEmpty()) {
            LOG.fine(String.format(
                    "[ref] lombok_scan owner=%s names=%s candidates=0 roots=0 compiler_errors=0 matches=0 total=%dms",
                    className, names, System.currentTimeMillis() - start));
            return List.of();
        }
        var groups = new LinkedHashMap<CompilerProvider, LinkedHashSet<Path>>();
        for (var candidate : files) {
            if (!candidateAllowed.test(declaration, candidate)) continue;
            var candidateCompiler = compilerForFile.apply(candidate);
            groups.computeIfAbsent(candidateCompiler, __ -> new LinkedHashSet<>())
                    .add(candidate);
        }
        var locations = new LinkedHashMap<String, Location>();
        var roots = 0;
        long errors = 0;
        for (var entry : groups.entrySet()) {
            try (var task = entry.getKey().compile(entry.getValue().toArray(Path[]::new))) {
                var paths = new ArrayList<TreePath>();
                for (var root : task.roots) {
                    new FindLombokReferences(task, names, className).scan(root, paths);
                }
                for (var path : paths) {
                    var location = FindHelper.location(task, path);
                    locations.put(FindHelper.locationKey(location), location);
                }
                roots += task.roots.size();
                errors += task.diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
            }
        }
        LOG.fine(String.format(
                "[ref] lombok_scan owner=%s names=%s candidates=%d roots=%d compiler_errors=%d matches=%d source=%s total=%dms",
                className,
                names,
                files.size(),
                roots,
                errors,
                locations.size(),
                useIndex ? "token_index" : "text_scan",
                System.currentTimeMillis() - start));
        return new ArrayList<>(locations.values());
    }
}
