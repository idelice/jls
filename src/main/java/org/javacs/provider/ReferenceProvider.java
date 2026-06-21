package org.javacs.provider;
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
import javax.lang.model.util.Elements;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LombokAnnotations;
import org.javacs.lsp.Location;
import org.javacs.navigation.FindLombokReferences;
import org.javacs.navigation.FindReferences;
import org.javacs.navigation.NavigationHelper;
import org.javacs.navigation.NavigationSymbolSupport;

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
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
            if (NavigationHelper.isMember(element)) {
                var parentClass = (TypeElement) element.getEnclosingElement();
                var className = parentClass.getQualifiedName().toString();
                var memberName = element.getSimpleName().toString();
                LOG.info(String.format("[ref] isMember kind=%s name=%s in=%s", element.getKind(), memberName, className));
                if (memberName.equals("<init>")) {
                    memberName = parentClass.getSimpleName().toString();
                }
                // Lombok gate first — skip all Lombok logic when not on classpath
                if (compiler.lombokPresentOnClasspath()) {
                    LOG.info("[ref] lombokOnClasspath=true");
                    LOG.info("[ref] calling lombokSearchNames");
                    var names = lombokSearchNames(element, memberName, task);
                    LOG.info(String.format("[ref] lombokSearchNames result size=%d", names.size()));
                    if (!names.isEmpty()) {
                        task.close();
                        return findLombokReferences(className, names);
                    }
                    LOG.info("[ref] names empty, falling back to findMemberReferences");
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
            return NOT_SUPPORTED;
        }
    }

    private List<Location> findTypeReferences(String className) {
        var files = compiler.findTypeReferences(className);
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findMemberReferences(String className, String memberName) {
        var files = compiler.findMemberReferences(className, memberName);
        if (files.length == 0) return List.of();
        try (var task = compiler.compile(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findReferences(CompileTask task) {
        var element = NavigationHelper.findElement(task, file, line, column);
        var paths = new ArrayList<TreePath>();
        for (var root : task.roots) {
            new FindReferences(task.task, element).scan(root, paths);
        }
        var locations = new ArrayList<Location>();
        for (var p : paths) {
            locations.add(FindHelper.location(task, p));
        }
        return locations;
    }

    private Set<String> lombokSearchNames(Element element, String memberName, CompileTask task) {
        var parent = element.getEnclosingElement();
        LOG.info(String.format("[ref] lombokSearchNames element.kind=%s memberName=%s", element.getKind(), memberName));
        if (!(parent instanceof TypeElement parentType)) {
            LOG.info("[ref] parent is not TypeElement, returning empty");
            return Set.of();
        }
        var hasLombok = hasLombokAnnotation(parentType.getQualifiedName().toString(), task.task.getElements());
        LOG.info(String.format("[ref] hasLombokAnnotation=%s", hasLombok));
        if (!hasLombok) return Set.of();
        var fieldName = element.getKind() == ElementKind.FIELD
                ? memberName
                : LombokAnnotations.accessorFieldName(memberName).orElse(null);
        LOG.info(String.format("[ref] fieldName=%s", fieldName));
        if (fieldName == null) {
            LOG.info("[ref] fieldName is null, returning empty");
            return Set.of();
        }
        var names = new LinkedHashSet<>(NavigationSymbolSupport.accessorNames(fieldName));
        names.add(fieldName);
        LOG.info(String.format("[ref] searchNames=%s", names));
        return names;
    }

    // check source TypeElement via task Elements. .class files strip SOURCE annotations.
    private boolean hasLombokAnnotation(String className, Elements elements) {
        var type = elements.getTypeElement(className);
        if (type == null) return false;
        for (var mirror : type.getAnnotationMirrors()) {
            var annType = mirror.getAnnotationType().asElement();
            if (annType instanceof TypeElement te
                    && LombokAnnotations.isStructuralLombokAnnotationType(te.getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    private List<Location> findLombokReferences(String className, Set<String> names) {
        LOG.info(String.format("[ref] findLombokReferences className=%s names=%s", className, names));
        var files = new LinkedHashSet<Path>();
        for (var name : names) {
            for (var f : compiler.findMemberReferences(className, name)) {
                files.add(f);
            }
        }
        LOG.info(String.format("[ref] candidate files count=%d", files.size()));
        if (files.isEmpty()) return List.of();
        try (var task = compiler.compile(files.toArray(Path[]::new))) {
            LOG.info("[ref] batch compile done");
            var paths = new ArrayList<TreePath>();
            for (var root : task.roots) {
                new FindLombokReferences(task.task, names).scan(root, paths);
            }
            LOG.info(String.format("[ref] paths found=%d", paths.size()));
            var locations = new ArrayList<Location>();
            for (var p : paths) {
                locations.add(FindHelper.location(task, p));
            }
            LOG.info(String.format("[ref] returning %d locations", locations.size()));
            return locations;
        }
    }
}
