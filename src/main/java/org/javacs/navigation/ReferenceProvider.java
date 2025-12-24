package org.javacs.navigation;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Types;
import java.util.logging.Logger;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.FileStore;
import org.javacs.StringSearch;
import org.javacs.index.WorkspaceIndex;
import org.javacs.lsp.Location;

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
        long t0 = System.nanoTime();
        long tResolveCompile0 = System.nanoTime();
        try (var task = compiler.compile(file)) {
            long tResolveCompile1 = System.nanoTime();
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
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
                VariableElement field = null;
                if (element.getKind() == ElementKind.FIELD) {
                    field = (VariableElement) element;
                } else if (element.getKind() == ElementKind.METHOD) {
                    // Lombok sometimes maps generated accessors to the field token; treat accessor-at-cursor as field.
                    field = findLombokBackingField(task, (ExecutableElement) element);
                }

                if (field != null) {
                    long tCandidate0 = System.nanoTime();
                    var files = new LinkedHashSet<Path>();
                    // Always include the declaration file so NavigationHelper.findElement can resolve the field element.
                    files.add(file);
                    var names = new ArrayList<String>();
                    memberName = field.getSimpleName().toString();
                    names.add(memberName);
                    names.addAll(accessorNames(field));
                    var indexed = WorkspaceIndex.filesContainingAny(names);
                    files.addAll(indexed);
                    long tCandidate1 = System.nanoTime();
                    task.close();
                    long tBatchCompile0 = System.nanoTime();
                    try (var t = compiler.compile(files.toArray(Path[]::new))) {
                        long tBatchCompile1 = System.nanoTime();
                        var target = ReferenceTarget.createForField(t, className, memberName);
                        var result = findReferences(t, target);
                        long tEnd = System.nanoTime();
                        LOG.info(
                                String.format(
                                "References(field) %s:%d:%d names=%s candidates=%d workspace=%d resolveCompile=%dms candidateScan=%dms batchCompile=%dms scan=%dms total=%dms",
                                        file.getFileName(),
                                        line,
                                        column,
                                        names,
                                        files.size(),
                                        FileStore.all().size(),
                                        (tResolveCompile1 - tResolveCompile0) / 1_000_000,
                                        (tCandidate1 - tCandidate0) / 1_000_000,
                                        (tBatchCompile1 - tBatchCompile0) / 1_000_000,
                                        (tEnd - tBatchCompile1) / 1_000_000,
                                        (tEnd - t0) / 1_000_000));
                        return result;
                    }
                }
                task.close();
                return findMemberReferences(className, memberName);
            }
            if (NavigationHelper.isLocal(element)) {
                return findReferences(task);
            }
            return NOT_SUPPORTED;
        } finally {
            // Keep per-kind logs above; this is a fallback to ensure we always get a timing line if needed.
            long tEnd = System.nanoTime();
            LOG.fine(
                    String.format(
                            "References total %s:%d:%d %dms",
                            file.getFileName(), line, column, (tEnd - t0) / 1_000_000));
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
        var target = ReferenceTarget.create(task, element);
        return findReferences(task, target);
    }

    private List<Location> findReferences(CompileTask task, ReferenceTarget target) {
        var paths = new ArrayList<TreePath>();
        for (var root : task.roots) {
            new FindReferences(task.task, target).scan(root, paths);
        }
        var locations = new ArrayList<Location>();
        var unique = new LinkedHashSet<String>();
        var trees = Trees.instance(task.task);
        var lineCache = new HashMap<Path, List<String>>();
        for (var p : paths) {
            var candidate = trees.getElement(p);
            if (candidate != null && candidate.getKind() == ElementKind.ANNOTATION_TYPE) {
                continue;
            }
            var name = candidate == null ? "" : candidate.getSimpleName().toString();
            var location = FindHelper.location(task, p, name);
            if (candidate instanceof ExecutableElement && target.field != null) {
                // Lombok can map generated methods to an arbitrary source range (often an annotation like @Data).
                // If the computed range doesn't actually point to the method name in source, drop it.
                if (isSpuriousGeneratedAccessorLocation(location, name, lineCache)) {
                    continue;
                }
            }
            var key =
                    location.uri
                            + ":"
                            + location.range.start.line
                            + ":"
                            + location.range.start.character
                            + "-"
                            + location.range.end.line
                            + ":"
                            + location.range.end.character;
            if (unique.add(key)) {
                locations.add(location);
            }
        }
        return locations;
    }

    private static boolean isSpuriousGeneratedAccessorLocation(
            Location location, String expectedName, Map<Path, List<String>> lineCache) {
        if (expectedName == null || expectedName.isEmpty()) return false;
        var path = toPath(location.uri);
        if (path == null) return false;
        var r = location.range;
        if (r == null || r.start == null || r.end == null) return false;
        if (r.start.line != r.end.line) return false;
        var lines = lineCache.computeIfAbsent(path, ReferenceProvider::readAllLinesQuietly);
        if (lines == null) return false;
        if (r.start.line < 0 || r.start.line >= lines.size()) return false;
        var line = lines.get(r.start.line);
        if (line == null) return false;
        int s = r.start.character;
        int e = r.end.character;
        if (s < 0 || e < 0 || s >= e || e > line.length()) return false;
        var selected = line.substring(s, e);
        if (selected.equals(expectedName)) return false;
        if (selected.equals("@" + expectedName)) return false;
        // If the range is (or is immediately preceded by) an annotation marker, treat it as a bogus "declaration".
        if (selected.indexOf('@') != -1) return true;
        if (s > 0 && line.charAt(s - 1) == '@') return true;
        var trimmed = stripLeadingWhitespace(line);
        if (trimmed.startsWith("@")) return true;
        return false;
    }

    private static String stripLeadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(i);
    }

    private static List<String> readAllLinesQuietly(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static Path toPath(URI uri) {
        if (uri == null) return null;
        if (uri.getScheme() != null && !uri.getScheme().equals("file")) return null;
        try {
            return Paths.get(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static VariableElement findLombokBackingField(CompileTask task, ExecutableElement method) {
        var enclosing = method.getEnclosingElement();
        if (!(enclosing instanceof TypeElement type)) return null;
        var name = method.getSimpleName().toString();
        var isGetter = method.getParameters().isEmpty() && (name.startsWith("get") || name.startsWith("is"));
        var isSetter = method.getParameters().size() == 1 && name.startsWith("set");
        if (!isGetter && !isSetter) return null;

        String base;
        if (name.startsWith("get")) base = name.substring("get".length());
        else if (name.startsWith("is")) base = name.substring("is".length());
        else base = name.substring("set".length());
        if (base.isEmpty()) return null;

        var property = decapitalize(base);
        var candidate1 = property;
        var candidate2 = "is" + ReferenceTarget.capitalize(property);
        var expectedType = isSetter ? method.getParameters().get(0).asType() : method.getReturnType();
        for (var e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.FIELD) continue;
            var f = (VariableElement) e;
            var fName = f.getSimpleName().toString();
            if (!fName.equals(candidate1) && !fName.equals(candidate2)) continue;
            if (task.task.getTypes().isSameType(f.asType(), expectedType)) {
                return f;
            }
        }
        return null;
    }

    private static String decapitalize(String input) {
        if (input == null || input.isEmpty()) return input;
        if (input.length() >= 2 && Character.isUpperCase(input.charAt(0)) && Character.isUpperCase(input.charAt(1))) {
            return input;
        }
        return Character.toLowerCase(input.charAt(0)) + input.substring(1);
    }

    static final class ReferenceTarget {
        final Element primary;
        final VariableElement field;
        final String capitalized;
        final String accessorCapitalized;
        final Types types;

        static ReferenceTarget create(CompileTask task, Element element) {
            var types = task.task.getTypes();
            if (element == null) return new ReferenceTarget(null, null, "", "", types);
            if (element.getKind() == ElementKind.FIELD) {
                var field = (VariableElement) element;
                var fieldName = field.getSimpleName().toString();
                var capitalized = capitalize(fieldName);
                var accessorCapitalized =
                        accessorCapitalized(fieldName, ReferenceProvider.isBooleanField(field));
                return new ReferenceTarget(element, field, capitalized, accessorCapitalized, types);
            }
            return new ReferenceTarget(element, null, "", "", types);
        }

        static ReferenceTarget createForField(CompileTask task, String declaringClass, String fieldName) {
            var types = task.task.getTypes();
            var type = task.task.getElements().getTypeElement(declaringClass);
            if (type != null) {
                for (var e : type.getEnclosedElements()) {
                    if (e.getKind() != ElementKind.FIELD) continue;
                    if (!e.getSimpleName().contentEquals(fieldName)) continue;
                    var field = (VariableElement) e;
                    var capitalized = capitalize(fieldName);
                    var accessorCapitalized =
                            accessorCapitalized(fieldName, ReferenceProvider.isBooleanField(field));
                    return new ReferenceTarget(field, field, capitalized, accessorCapitalized, types);
                }
            }
            return new ReferenceTarget(null, null, "", "", types);
        }

        ReferenceTarget(
                Element primary, VariableElement field, String capitalized, String accessorCapitalized, Types types) {
            this.primary = primary;
            this.field = field;
            this.capitalized = capitalized;
            this.accessorCapitalized = accessorCapitalized;
            this.types = types;
        }

        boolean matches(Element candidate) {
            if (primary != null && primary.equals(candidate)) return true;
            if (field == null || candidate == null) return false;
            if (!(candidate instanceof ExecutableElement method)) return false;
            return matchesGetter(method) || matchesSetter(method);
        }

        private boolean matchesGetter(ExecutableElement method) {
            if (!method.getParameters().isEmpty()) return false;
            if (!sameDeclaringType(method)) return false;
            var name = method.getSimpleName().toString();
            var fieldName = field.getSimpleName().toString();
            if (name.equals("get" + accessorCapitalized)
                    || (isBooleanField() && name.equals("is" + accessorCapitalized))) {
                return types.isSameType(method.getReturnType(), field.asType());
            }
            if (name.equals(fieldName)) {
                return types.isSameType(method.getReturnType(), field.asType());
            }
            return false;
        }

        private boolean matchesSetter(ExecutableElement method) {
            if (method.getParameters().size() != 1) return false;
            if (!sameDeclaringType(method)) return false;
            var name = method.getSimpleName().toString();
            if (!name.equals("set" + accessorCapitalized)) return false;
            var paramType = method.getParameters().get(0).asType();
            return types.isSameType(paramType, field.asType());
        }

        private boolean sameDeclaringType(ExecutableElement method) {
            var declaring = method.getEnclosingElement();
            var fieldDeclaring = field.getEnclosingElement();
            return declaring != null && declaring.equals(fieldDeclaring);
        }

        private boolean isBooleanField() {
            var kind = field.asType().getKind();
            if (kind == TypeKind.BOOLEAN) return true;
            if (kind != TypeKind.DECLARED) return false;
            var declared = (DeclaredType) field.asType();
            var element = declared.asElement();
            return element != null && element.toString().equals("java.lang.Boolean");
        }

        private static String capitalize(String input) {
            if (input == null || input.isEmpty()) return input;
            return input.substring(0, 1).toUpperCase() + input.substring(1);
        }

        private static String accessorCapitalized(String fieldName, boolean isBooleanField) {
            if (!isBooleanField) {
                return capitalize(fieldName);
            }
            // Lombok special-case: boolean field named `isActive` typically generates `isActive()/getActive()` and
            // `setActive(...)` (dropping the `is` prefix for the property name).
            if (fieldName != null
                    && fieldName.length() > 2
                    && fieldName.startsWith("is")
                    && Character.isUpperCase(fieldName.charAt(2))) {
                return fieldName.substring(2);
            }
            return capitalize(fieldName);
        }
    }

    private static List<String> accessorNames(VariableElement field) {
        var fieldName = field.getSimpleName().toString();
        var capitalized =
                ReferenceTarget.accessorCapitalized(fieldName, isBooleanField(field));
        var names = new ArrayList<String>();
        names.add("get" + capitalized);
        names.add("set" + capitalized);
        if (isBooleanField(field)) {
            names.add("is" + capitalized);
        }
        return names;
    }

    private static boolean isBooleanField(VariableElement field) {
        var kind = field.asType().getKind();
        if (kind == TypeKind.BOOLEAN) return true;
        if (kind != TypeKind.DECLARED) return false;
        var declared = (DeclaredType) field.asType();
        var element = declared.asElement();
        return element != null && element.toString().equals("java.lang.Boolean");
    }
}
