package org.javacs.provider;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.VariableTree;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.type.TypeKind;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.LombokAnnotations;
import org.javacs.SourceFileObject;
import org.javacs.lsp.Location;
import org.javacs.navigation.NavigationHelper;
import org.javacs.resolve.TypeNames;

public class DefinitionProvider {
    private static final Logger LOG = Logger.getLogger("main");

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
        var start = System.currentTimeMillis();
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
            if (element.asType().getKind() == TypeKind.ERROR) {
                if (compiler.lombokPresentOnClasspath()) {
                    var memberName = element.getSimpleName().toString();
                    var result = resolveLombokField(element, memberName, task.task.getElements());
                    if (!result.isEmpty()) return result;
                }
                return findError(element);
            }
            // TODO instead of checking isLocal, just try to resolve the location, fall back to searching
            if (NavigationHelper.isLocal(element)) {
                return findDefinitions(task, element);
            }
            var className = className(element);
            if (className.isEmpty()) return NOT_SUPPORTED;
            var otherFile = compiler.findAnywhere(className);
            if (otherFile.isEmpty()) {
                // Try navigating within current compilation (JDK, records, declarations)
                var localDefs = findDefinitions(task, element);
                if (!localDefs.isEmpty()) return localDefs;
                // Lombok builder inner class has no source → resolve via Lombok
                if (compiler.lombokPresentOnClasspath()) {
                    var memberName = element.getSimpleName().toString();
                    var result = resolveLombokField(element, memberName, task.task.getElements());
                    if (!result.isEmpty()) return result;
                }
                // Last resort: decompile from .class when no source is available
                var decompiled = compiler.decompileClass(className);
                if (decompiled.isPresent()) {
                    LOG.info("[def] decompileClass fallback for " + className + " at " + decompiled.get());
                    var parse = compiler.parse(new SourceFileObject(decompiled.get()));
                    var tree = FindHelper.findType(parse, className);
                    var path = TreePath.getPath(parse.root(), tree);
                    return List.of(FindHelper.location(parse, path, tree.getSimpleName()));
                }
                return List.of();
            }
            if (otherFile.get().toUri().equals(file.toUri())) {
                return findDefinitions(task, element);
            }
            // parse directly — javac Trees.getPath can't make TreePaths for jar:// sources
            var parse = compiler.parse(otherFile.get());
            var tree = FindHelper.findType(parse, className);
            // Navigate to the specific member if the element is a method
            if (element instanceof ExecutableElement method) {
                var erased = FindHelper.erasedParameterTypes(task, method);
                var memberTree = FindHelper.findMethod(parse, className, method.getSimpleName().toString(), erased);
                if (memberTree != null) {
                    var path = TreePath.getPath(parse.root(), memberTree);
                    return List.of(FindHelper.location(parse, path, method.getSimpleName()));
                }
            }
            // Navigate to the specific field/enum constant
            if (element.getKind() == ElementKind.FIELD || element.getKind() == ElementKind.ENUM_CONSTANT) {
                var memberName = element.getSimpleName().toString();
                try {
                    var memberTree = FindHelper.findField(parse, className, memberName);
                    var path = TreePath.getPath(parse.root(), memberTree);
                    return List.of(FindHelper.location(parse, path, memberName));
                } catch (RuntimeException e) {
                    // field not found in parsed source, fall through to class location
                }
            }
            var path = TreePath.getPath(parse.root(), tree);
            return List.of(FindHelper.location(parse, path, tree.getSimpleName()));
        } finally {
            LOG.info("[def] goto-definition " + file.getFileName() + ":" + line + ":" + column + " completed in " + (System.currentTimeMillis() - start) + "ms");
        }
    }

    private String resolveImportClass(String memberName) {
        var parse = compiler.parse(file);
        for (var imp : parse.root().getImports()) {
            if (!imp.isStatic()) continue;
            var qualifiedId = imp.getQualifiedIdentifier();
            if (!(qualifiedId instanceof MemberSelectTree select)) continue;
            var memberPart = select.getIdentifier().toString();
            // Explicit static import: import static org.example.Class.MEMBER
            if (memberPart.equals(memberName)) {
                var qid = qualifiedId.toString();
                return qid.substring(0, qid.lastIndexOf('.'));
            }
            // Star static import: import static org.example.Class.*
            if (memberPart.equals("*")) {
                var qid = qualifiedId.toString();
                var className = qid.substring(0, qid.lastIndexOf('.'));
                if (compiler.findAnywhere(className).isPresent() && !findAllMembers(className, memberName).isEmpty()) return className;
            }
        }
        return null;
    }

    private List<Location> findError(Element element) {
        var name = element.getSimpleName();
        if (name == null) return NOT_SUPPORTED;
        var memberName = name.toString();
        var parent = element.getEnclosingElement();
        if (parent instanceof TypeElement) {
            var type = (TypeElement) parent;
            var className = type.getQualifiedName().toString();
            // ERROR elements may have incomplete qualified names on this JDK
            if (!className.contains(".")) {
                var parse = compiler.parse(file);
                var resolved = TypeNames.resolveSimpleName(className, parse.root(),
                        fqn -> compiler.findAnywhere(fqn).isPresent());
                if (resolved.isEmpty()) return NOT_SUPPORTED;
                className = resolved.get();
            }
            var result = findAllMembers(className, memberName);
            if (!result.isEmpty()) return result;
        }
        // Try resolving via imports — handles static imports where the error
        // symbol's enclosing element is not a proper TypeElement
        var importClass = resolveImportClass(memberName);
        if (importClass != null) return findAllMembers(importClass, memberName);
        return List.of();
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
            for (var member : elements.getAllMembers(parentClass)) {
                if (!member.getSimpleName().contentEquals(memberName)) continue;
                var path = trees.getPath(member);
                if (path == null) continue;
                var location = FindHelper.location(task, path, memberName);
                locations.add(location);
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

    private List<Location> resolveLombokField(Element element, String memberName, Elements elements) {
        var fieldName = LombokAnnotations.accessorFieldName(memberName)
                .orElse(memberName.isEmpty() || !Character.isLowerCase(memberName.charAt(0))
                        ? null : memberName);
        if (fieldName == null) return List.of();
        var className = className(element);
        if (!className.isEmpty()) {
            var lastDot = className.lastIndexOf('.');
            while (lastDot > 0 && lastDot + 1 < className.length()) {
                var lastPart = className.substring(lastDot + 1);
                if (!Character.isLowerCase(lastPart.charAt(0))) break;
                className = className.substring(0, lastDot);
                lastDot = className.lastIndexOf('.');
            }
        }
        if (className.isEmpty()) return NOT_SUPPORTED;
        return navigateToField(className, fieldName, elements);
    }

    private List<Location> navigateToField(String startClassName, String fieldName, Elements elements) {
        var declaringClass = findDeclaringClass(startClassName, fieldName, elements);
        if (declaringClass.isEmpty()) return List.of();
        return parseAndNavigate(declaringClass, fieldName);
    }

    private List<Location> navigateToDeclaration(CompileTask task, ExecutableElement method) {
        var enclosing = (TypeElement) method.getEnclosingElement();
        var types = task.task.getTypes();
        var elements = task.task.getElements();
        for (var superMirror : types.directSupertypes(enclosing.asType())) {
            var superType = (TypeElement) types.asElement(superMirror);
            for (var other : superType.getEnclosedElements()) {
                if (!(other instanceof ExecutableElement superMethod)) continue;
                if (!elements.overrides(method, superMethod, enclosing)) continue;
                var superClass = superType.getQualifiedName().toString();
                var sourceFile = compiler.findAnywhere(superClass);
                if (sourceFile.isEmpty()) continue;
                var parse = compiler.parse(sourceFile.get());
                var erased = FindHelper.erasedParameterTypes(task, method);
                var tree = FindHelper.findMethod(parse, superClass, method.getSimpleName().toString(), erased);
                var path = TreePath.getPath(parse.root(), tree);
                return List.of(FindHelper.location(parse, path, method.getSimpleName()));
            }
        }
        return List.of();
    }

    private List<Location> navigateToRecordComponent(CompileTask task, Element element) {
        var enclosing = element.getEnclosingElement();
        if (!(enclosing instanceof TypeElement type) || type.getKind() != ElementKind.RECORD) {
            return List.of();
        }
        var accessorName = element.getSimpleName().toString();
        // Try Elements API first — handles records in the current compilation
        for (var member : type.getEnclosedElements()) {
            if (member.getKind() == ElementKind.RECORD_COMPONENT
                    && member.getSimpleName().contentEquals(accessorName)) {
                var trees = Trees.instance(task.task);
                var path = trees.getPath(member);
                if (path != null) {
                    return List.of(FindHelper.location(task, path, accessorName));
                }
            }
        }
        // Fall back to parsing source and scanning members
        var recordClass = className(element);
        if (recordClass.isEmpty()) return List.of();
        var sourceFile = compiler.findAnywhere(recordClass);
        if (sourceFile.isEmpty()) return List.of();
        var parse = compiler.parse(sourceFile.get());
        var classTree = FindHelper.findType(parse, recordClass);
        for (var member : classTree.getMembers()) {
            if (member instanceof VariableTree vt && vt.getName().contentEquals(accessorName)) {
                var path = TreePath.getPath(parse.root(), vt);
                return List.of(FindHelper.location(parse, path, accessorName));
            }
        }
        return List.of();
    }

    private String findDeclaringClass(String startClass, String fieldName, Elements elements) {
        var current = elements.getTypeElement(startClass);
        while (current != null) {
            for (var member : current.getEnclosedElements()) {
                if (member.getKind() != ElementKind.FIELD) continue;
                if (!member.getSimpleName().contentEquals(fieldName)) continue;
                return current.getQualifiedName().toString();
            }
            var superMirror = current.getSuperclass();
            if (superMirror.getKind() != TypeKind.DECLARED) break;
            current = (TypeElement) ((DeclaredType) superMirror).asElement();
        }
        return "";
    }

    private List<Location> parseAndNavigate(String className, String fieldName) {
        var sourceFile = compiler.findAnywhere(className);
        if (sourceFile.isEmpty()) return List.of();
        var parse = compiler.parse(sourceFile.get());
        var fieldTree = FindHelper.findField(parse, className, fieldName);
        var path = TreePath.getPath(parse.root(), fieldTree);
        return List.of(FindHelper.location(parse, path, fieldName));
    }

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

    private List<Location> tryDeclarationNavigation(CompileTask task, Element element) {
        if (element instanceof ExecutableElement method) {
            var decl = navigateToDeclaration(task, method);
            if (!decl.isEmpty()) return decl;
        }
        return navigateToRecordComponent(task, element);
    }

    private List<Location> findDefinitions(CompileTask task, Element element) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(element);
        if (path == null) {
            if (compiler.lombokPresentOnClasspath()) {
                var rawClassName = className(element);
                if (hasLombokAnnotation(rawClassName, task.task.getElements())) {
                    return resolveLombokField(element, element.getSimpleName().toString(), task.task.getElements());
                }
                // .class files strip SOURCE annotations. Try accessor pattern fallback.
                var memberName = element.getSimpleName().toString();
                if (LombokAnnotations.accessorFieldName(memberName).isPresent()) {
                    return resolveLombokField(element, memberName, task.task.getElements());
                }
            }
            var decl = tryDeclarationNavigation(task, element);
            if (!decl.isEmpty()) return decl;
            return List.of();
        }
        if (element instanceof ExecutableElement method) {
            var decl = navigateToDeclaration(task, method);
            if (!decl.isEmpty()) return decl;
        }
        var rec = navigateToRecordComponent(task, element);
        if (!rec.isEmpty()) return rec;
        var name = element.getSimpleName();
        if (name.contentEquals("<init>")) name = element.getEnclosingElement().getSimpleName();
        return List.of(FindHelper.location(task, path, name));
    }
}
