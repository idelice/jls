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
            java.util.logging.Logger.getLogger("main").info("...DefinitionProvider element: " + element);
            if (element == null) {
                java.util.logging.Logger.getLogger("main").info("...element is null");
                return NOT_SUPPORTED;
            }
            java.util.logging.Logger.getLogger("main").info("...element type kind: " + element.asType().getKind());
            if (element.asType().getKind() == TypeKind.ERROR) {
                java.util.logging.Logger.getLogger("main").info("...element has ERROR type, calling findError");
                task.close();
                return findError(element);
            }
            // TODO instead of checking isLocal, just try to resolve the location, fall back to searching
            if (NavigationHelper.isLocal(element)) {
                return findDefinitions(task, element);
            }

            // Special handling for constructors
            if (element.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR) {
                // First try with current task (same-file constructor)
                var result = findDefinitions(task, element);
                if (!result.isEmpty()) {
                    return result;
                }
                // For cross-file constructors, we need to compile both files
                var enclosingType = (TypeElement) element.getEnclosingElement();
                var className = enclosingType.getQualifiedName().toString();
                var otherFile = compiler.findAnywhere(className);
                if (otherFile.isPresent() && !otherFile.get().toUri().equals(file.toUri())) {
                    task.close();
                    return findRemoteDefinitionsLocal(otherFile.get(), element);
                }
                return result;
            }

            // Check if this is a constructor call (new expression)
            if (NavigationHelper.isType(element)) {
                var constructors = findConstructorForContext(task, (TypeElement) element);
                if (!constructors.isEmpty()) {
                    return constructors;
                }
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
        java.util.logging.Logger.getLogger("main").info("...findError element name: " + name);
        if (name == null) return NOT_SUPPORTED;
        var parent = element.getEnclosingElement();
        java.util.logging.Logger.getLogger("main").info("...findError parent: " + parent);
        if (!(parent instanceof TypeElement)) {
            java.util.logging.Logger.getLogger("main").info("...parent is not TypeElement, returning NOT_SUPPORTED");
            return NOT_SUPPORTED;
        }
        var type = (TypeElement) parent;
        var className = type.getQualifiedName().toString();
        var memberName = name.toString();
        java.util.logging.Logger.getLogger("main").info("...findError className: " + className);

        // Check if className looks like a method chain (e.g., "Foo.getBar" instead of just "Bar")
        // This happens with nested Lombok getter calls where javac can't resolve the type
        if (isMethodChain(className)) {
            java.util.logging.Logger.getLogger("main").info("...detected method chain, attempting to resolve return type");
            var resolvedClassName = resolveMethodChainReturnType(className);
            if (resolvedClassName != null) {
                className = resolvedClassName;
                java.util.logging.Logger.getLogger("main").info("...resolved to class: " + className);
            }
        }

        java.util.logging.Logger.getLogger("main").info("...findError searching for member: " + memberName + " in class: " + className);
        return findAllMembers(className, memberName);
    }

    /**
     * Check if a class name looks like a method chain (e.g., "Foo.getBar" instead of a proper class name).
     * Method names typically start with lowercase letters.
     */
    private boolean isMethodChain(String className) {
        var lastDot = className.lastIndexOf('.');
        if (lastDot < 0) return false;
        var lastPart = className.substring(lastDot + 1);
        // Check if the last part starts with a lowercase letter (likely a method name)
        return !lastPart.isEmpty() && Character.isLowerCase(lastPart.charAt(0));
    }

    /**
     * Resolve the return type of a method chain like "com.example.Foo.getBar" to "com.example.Bar".
     * This handles Lombok-generated getters and works recursively for nested chains like "Foo.getBar.getBiz".
     */
    private String resolveMethodChainReturnType(String methodChain) {
        var lastDot = methodChain.lastIndexOf('.');
        if (lastDot < 0) return null;

        var className = methodChain.substring(0, lastDot);
        var methodName = methodChain.substring(lastDot + 1);

        java.util.logging.Logger.getLogger("main").info("...resolving method: " + methodName + " on class: " + className);

        // Check if className is itself a method chain (recursive case)
        if (isMethodChain(className)) {
            java.util.logging.Logger.getLogger("main").info("...className is also a method chain, resolving recursively");
            className = resolveMethodChainReturnType(className);
            if (className == null) {
                java.util.logging.Logger.getLogger("main").info("...recursive resolution failed");
                return null;
            }
            java.util.logging.Logger.getLogger("main").info("...recursive resolution succeeded: " + className);
        }

        // Find the class
        var classFile = compiler.findAnywhere(className);
        if (classFile.isEmpty()) {
            java.util.logging.Logger.getLogger("main").info("...class file not found: " + className);
            return null;
        }

        try (var task = compiler.compile(List.of(classFile.get()))) {
            var elements = task.task.getElements();
            var typeElement = elements.getTypeElement(className);
            if (typeElement == null) {
                java.util.logging.Logger.getLogger("main").info("...type element not found: " + className);
                return null;
            }

            // Look for the field that corresponds to this getter
            String fieldName = null;
            if (methodName.startsWith("get") && methodName.length() > 3) {
                fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (methodName.startsWith("is") && methodName.length() > 2) {
                fieldName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
            }

            if (fieldName == null) {
                java.util.logging.Logger.getLogger("main").info("...not a getter pattern: " + methodName);
                return null;
            }

            java.util.logging.Logger.getLogger("main").info("...looking for field: " + fieldName);

            // Find the field and get its type
            for (var member : elements.getAllMembers(typeElement)) {
                if (member.getKind() == javax.lang.model.element.ElementKind.FIELD
                        && member.getSimpleName().toString().equals(fieldName)) {
                    var fieldType = member.asType();
                    if (fieldType instanceof javax.lang.model.type.DeclaredType) {
                        var returnTypeElement = (TypeElement) ((javax.lang.model.type.DeclaredType) fieldType).asElement();
                        var returnClassName = returnTypeElement.getQualifiedName().toString();
                        java.util.logging.Logger.getLogger("main").info("...found return type: " + returnClassName);
                        return returnClassName;
                    }
                }
            }
        }

        java.util.logging.Logger.getLogger("main").info("...could not resolve return type");
        return null;
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

            // For type references, try to detect if we're in a constructor call
            if (elementFromLocal != null && NavigationHelper.isType(elementFromLocal)) {
                var constructors = findConstructorForContext(task, (TypeElement) elementFromLocal);
                if (!constructors.isEmpty()) {
                    return constructors;
                }
            }

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

    private List<Location> findConstructorForContext(CompileTask task, TypeElement typeElement) {
        var trees = Trees.instance(task.task);
        var sourcePositions = trees.getSourcePositions();
        var compilationUnit = task.roots.stream()
                .filter(root -> root.getSourceFile().toUri().equals(file.toUri()))
                .findFirst()
                .orElse(null);

        if (compilationUnit == null) return List.of();

        // Convert line/column to position offset using LineMap
        long cursorPos = compilationUnit.getLineMap().getPosition(line, column);

        // Check if cursor is inside a constructor definition or NewClassTree
        class ConstructorFinder extends com.sun.source.util.TreePathScanner<List<Location>, Object> {
            @Override
            public List<Location> visitClass(com.sun.source.tree.ClassTree tree, Object p) {
                // Only scan the right class
                if (tree.getSimpleName().toString().equals(typeElement.getSimpleName().toString())) {
                    // Look for constructor methods inside this class
                    for (var member : tree.getMembers()) {
                        if (member instanceof com.sun.source.tree.MethodTree) {
                            var method = (com.sun.source.tree.MethodTree) member;
                            if (method.getName().toString().equals(typeElement.getSimpleName().toString())) {
                                try {
                                    long startPos = sourcePositions.getStartPosition(compilationUnit, method);
                                    long endPos = sourcePositions.getEndPosition(compilationUnit, method);
                                    if (cursorPos >= startPos && cursorPos <= endPos) {
                                        // Cursor is inside this constructor!
                                        var element = trees.getElement(trees.getPath(compilationUnit, method));
                                        if (element != null) {
                                            var path = trees.getPath(element);
                                            if (path != null) {
                                                return List.of(FindHelper.location(task, path, typeElement.getSimpleName()));
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    // Ignore
                                }
                            }
                        }
                    }
                }
                var result = super.visitClass(tree, p);
                return result != null ? result : null;
            }

            @Override
            public List<Location> visitNewClass(com.sun.source.tree.NewClassTree tree, Object p) {
                try {
                    // Check if this NewClass has the right type
                    var exprType = trees.getTypeMirror(getCurrentPath());
                    if (exprType != null && exprType.toString().equals(typeElement.getQualifiedName().toString())) {
                        long startPos = sourcePositions.getStartPosition(compilationUnit, tree);
                        long endPos = sourcePositions.getEndPosition(compilationUnit, tree);

                        if (cursorPos >= startPos && cursorPos <= endPos) {
                            // We're inside this new expression!
                            // Find the matching constructor by argument count
                            int argCount = tree.getArguments().size();

                            // Try to find matching constructor using getAllMembers which works for both local and remote
                            var allMembers = task.task.getElements().getAllMembers(typeElement);
                            for (var member : allMembers) {
                                if (member.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR
                                    && member instanceof ExecutableElement) {
                                    var exec = (ExecutableElement) member;
                                    if (exec.getParameters().size() == argCount) {
                                        var treePath = trees.getPath(member);
                                        if (treePath != null) {
                                            return List.of(FindHelper.location(task, treePath, typeElement.getSimpleName()));
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore and continue
                }
                var result = super.visitNewClass(tree, p);
                return result != null ? result : null;
            }
        }

        var result = new ConstructorFinder().scan(compilationUnit, null);
        return result != null ? result : List.of();
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
