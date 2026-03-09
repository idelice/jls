package org.javacs.navigation;

import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.Location;

public class ReferenceProvider {
    private final CompilerProvider compiler;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public ReferenceProvider(CompilerProvider compiler, Path file, int line, int column) {
        this.compiler = compiler;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        try (var task = compiler.compileFastWithProcessors(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element != null) {
                return find(task, element);
            }
        }
        return NOT_SUPPORTED;
    }

    private List<Location> find(CompileTask task, Element element) {
        if (NavigationHelper.isType(element)) {
            var type = (TypeElement) element;
            var className = type.getQualifiedName().toString();
            task.close();
            return findTypeReferences(className);
        }
        if (NavigationHelper.isMember(element)) {
            var parentClass = (TypeElement) element.getEnclosingElement();
            var className = parentClass.getQualifiedName().toString();
            var memberName = element.getSimpleName().toString();
            if (memberName.equals("<init>")) {
                memberName = parentClass.getSimpleName().toString();
            }
            task.close();
            if (element.getKind() == javax.lang.model.element.ElementKind.FIELD) {
                return findFieldReferencesScoped(className, memberName);
            }
            return findMemberReferences(className, memberName);
        }
        if (NavigationHelper.isLocal(element)) {
            return findReferences(task);
        }
        return NOT_SUPPORTED;
    }

    private List<Location> findTypeReferences(String className) {
        var files = compiler.findTypeReferences(className);
        if (files.length == 0) return List.of();
        try (var task = compiler.compileFastWithProcessors(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findMemberReferences(String className, String memberName) {
        var files = compiler.findMemberReferences(className, memberName);
        if (files.length == 0) return List.of();
        try (var task = compiler.compileFastWithProcessors(files)) {
            return findReferences(task);
        }
    }

    private List<Location> findFieldReferencesScoped(String className, String memberName) {
        var files = compiler.findTypeReferences(className);
        var classFile = compiler.findTypeDeclaration(className);
        if (classFile != null && !classFile.equals(CompilerProvider.NOT_FOUND)) {
            var combined = new java.util.ArrayList<Path>();
            for (var f : files) {
                combined.add(f);
            }
            if (!combined.contains(classFile)) {
                combined.add(classFile);
            }
            files = combined.toArray(Path[]::new);
        }
        if (files.length == 0) return List.of();
        try (var task = compiler.compileFastWithProcessors(files)) {
            var refs = findFieldAndAccessorReferences(task, className, memberName);
            if (!refs.isEmpty()) {
                return refs;
            }
        }
        try (var task = compiler.compileFastWithProcessors(files)) {
            return findFieldAndAccessorReferences(task, className, memberName);
        }
    }

    private List<Location> findReferences(CompileTask task) {
        var element = NavigationHelper.findElement(task, file, line, column);
        var expectedName = referenceName(element);
        var paths = new ArrayList<TreePath>();
        for (var root : task.roots) {
            new FindReferences(task.task, element).scan(root, paths);
        }
        var locations = new ArrayList<Location>();
        for (var p : paths) {
            var location = expectedName == null ? FindHelper.location(task, p) : FindHelper.locationStrict(task, p, expectedName);
            if (location == null) continue;
            locations.add(location);
        }
        return locations;
    }

    private List<Location> findFieldAndAccessorReferences(CompileTask task, String className, String fieldName) {
        var targets = new ArrayList<Element>();
        var selected = NavigationHelper.findElement(task, file, line, column);
        if (selected != null) {
            targets.add(selected);
        }

        var owner = task.task.getElements().getTypeElement(className);
        if (owner != null) {
            var accessorNames = accessorNames(fieldName);
            for (var enclosed : owner.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD
                        && accessorNames.contains(enclosed.getSimpleName().toString())) {
                    targets.add(enclosed);
                }
            }
        }

        var dedup = new LinkedHashMap<String, Location>();
        for (var target : targets) {
            var expectedName = referenceName(target);
            if (expectedName == null) continue;
            var paths = new ArrayList<TreePath>();
            for (var root : task.roots) {
                new FindReferences(task.task, target).scan(root, paths);
            }
            for (var path : paths) {
                var location = FindHelper.locationStrict(task, path, expectedName);
                if (location == null) continue;
                var key = location.uri + ":" + location.range.start.line + ":" + location.range.start.character + ":"
                        + location.range.end.line + ":" + location.range.end.character;
                dedup.putIfAbsent(key, location);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private Set<String> accessorNames(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return Set.of();
        }
        var base = fieldName.substring(0, 1).toUpperCase(Locale.ROOT) + fieldName.substring(1);
        return Set.of("get" + base, "is" + base, "set" + base);
    }

    private CharSequence referenceName(Element element) {
        if (element == null) {
            return null;
        }
        if (element.getKind() == ElementKind.CONSTRUCTOR) {
            var enclosing = element.getEnclosingElement();
            if (enclosing instanceof TypeElement) {
                return ((TypeElement) enclosing).getSimpleName();
            }
            return null;
        }
        return element.getSimpleName();
    }
}
