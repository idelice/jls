package org.javacs.provider;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LombokAnnotations;
import org.javacs.lsp.Location;
import org.javacs.navigation.FindLombokReferences;
import org.javacs.navigation.FindReferences;
import org.javacs.navigation.NavigationHelper;

public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    private static final Logger LOG = Logger.getLogger("main");

    public ReferenceProvider(CompilerProvider compiler, Path file, int line, int column) {
        this.compiler = compiler;
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
                LOG.fine(String.format("[ref] isMember kind=%s name=%s in=%s", element.getKind(), memberName, className));
                if (memberName.equals("<init>")) {
                    memberName = parentClass.getSimpleName().toString();
                }
                // Lombok gate first — skip all Lombok logic when not on classpath
                if (compiler.lombokPresentOnClasspath()) {
                    LOG.fine("[ref] lombokOnClasspath=true");
                    var names = lombokSearchNames(element, memberName, task);
                    if (!names.isEmpty()) {
                        task.close();
                        return findLombokReferences(className, names);
                    }
                    LOG.fine("[ref] names empty, falling back to findMemberReferences");
                }
                task.close();
                return findMemberReferences(className, memberName);
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
        try (var task = compiler.compileFresh(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findMemberReferences(String className, String memberName) {
        var files = compiler.findMemberReferences(className, memberName);
        LOG.fine(String.format(
                "[ref] member_scan owner=%s name=%s candidates=%d", className, memberName, files.length));
        if (files.length == 0) return List.of();
        try (var task = compiler.compileFresh(files)) {
            return findReferences(task);
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
        try (var task = compiler.compileFresh(files.toArray(Path[]::new))) {
            var paths = new ArrayList<TreePath>();
            for (var root : task.roots) {
                new FindLombokReferences(task, names, className).scan(root, paths);
            }
            var locations = new ArrayList<Location>();
            for (var p : paths) {
                locations.add(FindHelper.location(task, p));
            }
            var errors = task.diagnostics.stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
            LOG.fine(String.format(
                    "[ref] lombok_scan owner=%s names=%s candidates=%d roots=%d compiler_errors=%d matches=%d total=%dms",
                    className,
                    names,
                    files.size(),
                    task.roots.size(),
                    errors,
                    locations.size(),
                    System.currentTimeMillis() - start));
            return locations;
        }
    }
}
