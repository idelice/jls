package org.javacs.navigation;

import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
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

            // For JAR files, use simplified logic that only matches by name
            var uri = otherFile.get().toUri();
            if ("jar".equals(uri.getScheme()) || uri.getPath().contains("jls-jar-sources")) {
                return findRemoteDefinitions(otherFile.get(), className, element);
            }

            // For workspace files, use original logic that better handles overloading
            return findRemoteDefinitionsLocal(otherFile.get(), element);
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
        if (otherFile.get().toUri().equals(file.toUri())) {
            sources = List.of(fileAsSource);
        }
        var locations = new ArrayList<Location>();
        try (var task = compiler.compile(sources)) {
            var trees = Trees.instance(task.task);
            var elements = task.task.getElements();
            var parentClass = elements.getTypeElement(className);

            // First, try to find Lombok-generated members (these take priority)
            locations.addAll(LombokHandler.findGeneratedMemberLocations(task, className, memberName));

            // Then, find instance methods with this name (skip static methods)
            // For records, prefer METHOD over FIELD (both have the same name)
            javax.lang.model.element.Element methodMember = null;
            javax.lang.model.element.Element fieldMember = null;

            for (var member : elements.getAllMembers(parentClass)) {
                if (!member.getSimpleName().contentEquals(memberName)) continue;
                // Skip static methods when looking for instance method definitions
                if (member instanceof ExecutableElement) {
                    var method = (ExecutableElement) member;
                    if (method.getModifiers().contains(Modifier.STATIC)) {
                        continue;
                    }
                    methodMember = member;
                } else if (member instanceof VariableElement && fieldMember == null) {
                    fieldMember = member;
                }
            }

            // Prefer METHOD (for record accessors) over FIELD
            var targetMember = methodMember != null ? methodMember : fieldMember;
            if (targetMember != null) {
                var path = trees.getPath(targetMember);
                if (path != null) {
                    var location = FindHelper.location(task, path, memberName);
                    locations.add(location);
                }
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

    private List<Location> findRemoteDefinitionsLocal(JavaFileObject otherFile, Element element) {
        try (var task = compiler.compile(List.of(new SourceFileObject(file), otherFile))) {
            var elementFromLocal = NavigationHelper.findElement(task, file, line, column);
            return findDefinitions(task, elementFromLocal);
        }
    }

    private List<Location> findRemoteDefinitions(JavaFileObject otherFile, String className, Element element) {
        try (var task = compiler.compile(List.of(otherFile))) {
            var elements = task.task.getElements();
            var typeElement = elements.getTypeElement(className);
            if (typeElement == null) {
                return List.of();
            }
            var trees = com.sun.source.util.Trees.instance(task.task);

            // Try to find a matching member if the element is a member
            if (element instanceof ExecutableElement || element instanceof VariableElement) {
                for (var member : typeElement.getEnclosedElements()) {
                    if (member.getSimpleName().contentEquals(element.getSimpleName())) {
                        var memberPath = trees.getPath(member);
                        if (memberPath != null) {
                            // For constructors, use the class name instead of <init>
                            var displayName = "<init>".equals(element.getSimpleName().toString())
                                ? typeElement.getSimpleName()
                                : element.getSimpleName();
                            return List.of(FindHelper.location(task, memberPath, displayName));
                        }
                    }
                }
            }

            // Fall back to type definition
            var path = trees.getPath(typeElement);
            if (path == null) {
                return List.of();
            }
            return List.of(FindHelper.location(task, path, typeElement.getSimpleName()));
        }
    }

    private List<Location> findDefinitions(CompileTask task, Element element) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(element);

        if (path == null) {
            // For synthetic methods (like record accessors), navigate to the parameter in the record declaration
            if (element instanceof ExecutableElement) {
                var enclosing = element.getEnclosingElement();
                if (enclosing != null && enclosing.getKind() == javax.lang.model.element.ElementKind.RECORD) {
                    var recordElement = (TypeElement) enclosing;
                    var recordPath = trees.getPath(recordElement);
                    if (recordPath != null) {
                        // Use the method name (which matches the parameter name) to find the parameter location
                        var paramName = element.getSimpleName();
                        return List.of(FindHelper.location(task, recordPath, paramName));
                    }
                }
            }
            return List.of();
        }
        var name = element.getSimpleName();
        if (name.contentEquals("<init>")) name = element.getEnclosingElement().getSimpleName();
        return List.of(FindHelper.location(task, path, name));
    }
}
