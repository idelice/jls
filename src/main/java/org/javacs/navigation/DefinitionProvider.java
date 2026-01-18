package org.javacs.navigation;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LombokHandler;
import org.javacs.SourceFileObject;
import org.javacs.lsp.Location;

public class DefinitionProvider {
    private final CompilerProvider compiler;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public DefinitionProvider(CompilerProvider compiler, Path file, int line, int column) {
        this.compiler = compiler;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
            if (element.asType().getKind() == TypeKind.ERROR) {
                task.close();
                return findError(element);
            }
            // TODO instead of checking isLocal, just try to resolve the location, fall back to searching
            if (NavigationHelper.isLocal(element)) {
                return findDefinitions(task, element);
            }
            var className = className(element);
            if (className.isEmpty()) return NOT_SUPPORTED;
            var otherFile = compiler.findAnywhere(className);
            if (otherFile.isEmpty()) return List.of();
            if (otherFile.get().toUri().equals(file.toUri())) {
                return findDefinitions(task, element);
            }
            task.close();
            return findRemoteDefinitions(otherFile.get());
        }
    }

    private List<Location> findError(Element element) {
        var name = element.getSimpleName();
        if (name == null) return NOT_SUPPORTED;
        var parent = element.getEnclosingElement();
        if (!(parent instanceof TypeElement)) return NOT_SUPPORTED;
        var type = (TypeElement) parent;
        var className = type.getQualifiedName().toString();
        var memberName = name.toString();
        return findAllMembers(className, memberName);
    }

    private List<Location> findAllMembers(String className, String memberName) {
        var otherFile = compiler.findAnywhere(className);
        if (otherFile.isEmpty()) return List.of();
        var fileAsSource = new SourceFileObject(file);
        var sources = List.of(fileAsSource, otherFile.get());
        if (otherFile.get().toString().equals(file.toUri())) {
            sources = List.of(fileAsSource);
        }
        var locations = new ArrayList<Location>();
        try (var task = compiler.compile(sources)) {
            var trees = Trees.instance(task.task);
            var elements = task.task.getElements();
            var parentClass = elements.getTypeElement(className);

            // First, try to find the member normally
            for (var member : elements.getAllMembers(parentClass)) {
                if (!member.getSimpleName().contentEquals(memberName)) continue;
                var path = trees.getPath(member);
                if (path == null) continue;
                var location = FindHelper.location(task, path, memberName);
                locations.add(location);
            }

            // If not found, check if it's a Lombok-generated member
            if (locations.isEmpty()) {
                locations.addAll(LombokHandler.findGeneratedMemberLocations(task, className, memberName));
            }
        }
        return locations;
    }

    private String className(Element element) {
        while (element != null) {
            if (element instanceof TypeElement) {
                var type = (TypeElement) element;
                return type.getQualifiedName().toString();
            }
            element = element.getEnclosingElement();
        }
        return "";
    }

    private List<Location> findRemoteDefinitions(JavaFileObject otherFile) {
        try (var task = compiler.compile(List.of(new SourceFileObject(file), otherFile))) {
            var element = NavigationHelper.findElement(task, file, line, column);
            return findDefinitions(task, element);
        }
    }

    private List<Location> findDefinitions(CompileTask task, Element element) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(element);
        if (path == null) {
            return List.of();
        }
        var name = element.getSimpleName();
        if (name.contentEquals("<init>")) name = element.getEnclosingElement().getSimpleName();
        return List.of(FindHelper.location(task, path, name));
    }
}
