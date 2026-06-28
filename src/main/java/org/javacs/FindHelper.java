package org.javacs;

import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.javacs.CacheAudit;
import org.javacs.lsp.Location;
import org.javacs.lsp.Range;

public class FindHelper {
    private static final Map<URI, URI> jarUriCache = new ConcurrentHashMap<>();
    private static Path jarCacheDir;

    public static String[] erasedParameterTypes(CompileTask task, ExecutableElement method) {
        var types = task.types;
        var erasedParameterTypes = new String[method.getParameters().size()];
        for (var i = 0; i < erasedParameterTypes.length; i++) {
            var p = method.getParameters().get(i).asType();
            erasedParameterTypes[i] = types.erasure(p).toString();
        }
        return erasedParameterTypes;
    }

    public static MethodTree findMethod(
            ParseTask task, String className, String methodName, String[] erasedParameterTypes) {
        var classTree = findType(task, className);
        for (var member : classTree.getMembers()) {
            if (member.getKind() != Tree.Kind.METHOD) continue;
            var method = (MethodTree) member;
            if (!method.getName().contentEquals(methodName)) continue;
            if (!isSameMethodType(method, erasedParameterTypes)) continue;
            return method;
        }
        throw new RuntimeException("no method");
    }

    public static VariableTree findField(ParseTask task, String className, String memberName) {
        var classTree = findType(task, className);
        for (var member : classTree.getMembers()) {
            if (member.getKind() != Tree.Kind.VARIABLE) continue;
            var variable = (VariableTree) member;
            if (!variable.getName().contentEquals(memberName)) continue;
            return variable;
        }
        throw new RuntimeException("no variable");
    }

    public static ClassTree findType(ParseTask task, String className) {
        return new FindTypeDeclarationNamed().scan(task.root(), className);
    }

    public static ExecutableElement findMethod(
            CompileTask task, String className, String methodName, String[] erasedParameterTypes) {
        var type = task.elements.getTypeElement(className);
        for (var member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) continue;
            var method = (ExecutableElement) member;
            if (isSameMethod(task, method, className, methodName, erasedParameterTypes)) {
                return method;
            }
        }
        return null;
    }

    private static boolean isSameMethod(
            CompileTask task,
            ExecutableElement method,
            String className,
            String methodName,
            String[] erasedParameterTypes) {
        var types = task.types;
        var parent = (TypeElement) method.getEnclosingElement();
        if (!parent.getQualifiedName().contentEquals(className)) return false;
        if (!method.getSimpleName().contentEquals(methodName)) return false;
        if (method.getParameters().size() != erasedParameterTypes.length) return false;
        for (var i = 0; i < erasedParameterTypes.length; i++) {
            var erasure = types.erasure(method.getParameters().get(i).asType());
            var same = erasure.toString().equals(erasedParameterTypes[i]);
            if (!same) return false;
        }
        return true;
    }

    private static boolean isSameMethodType(MethodTree candidate, String[] erasedParameterTypes) {
        if (candidate.getParameters().size() != erasedParameterTypes.length) {
            return false;
        }
        for (var i = 0; i < candidate.getParameters().size(); i++) {
            if (!typeMatches(candidate.getParameters().get(i).getType(), erasedParameterTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean typeMatches(Tree candidate, String erasedType) {
        if (candidate instanceof ParameterizedTypeTree parameterized) {
            return typeMatches(parameterized.getType(), erasedType);
        }
        if (candidate instanceof PrimitiveTypeTree) {
            return candidate.toString().equals(erasedType);
        }
        if (candidate instanceof IdentifierTree) {
            var simpleName = candidate.toString();
            return erasedType.endsWith(simpleName);
        }
        if (candidate instanceof MemberSelectTree) {
            return candidate.toString().equals(erasedType);
        }
        if (candidate instanceof ArrayTypeTree array) {
            if (!erasedType.endsWith("[]")) return false;
            var erasedElement = erasedType.substring(0, erasedType.length() - "[]".length());
            return typeMatches(array.getType(), erasedElement);
        }
        return true;
    }

    public static Location location(ParseTask task, TreePath path) {
        return location(Trees.instance(task.task()), path, "", false);
    }

    public static Location location(CompileTask task, TreePath path) {
        return location(task.trees, path, "", false);
    }

    public static Location location(CompileTask task, TreePath path, CharSequence name) {
        return location(task.trees, path, name, false);
    }

    public static Location location(ParseTask task, TreePath path, CharSequence name) {
        return location(Trees.instance(task.task()), path, name, false);
    }

    public static Location locationStrict(ParseTask task, TreePath path, CharSequence name) {
        return location(Trees.instance(task.task()), path, name, true);
    }

    public static Location location(Trees trees, TreePath path, CharSequence name, boolean strictNameMatch) {
        var pos = trees.getSourcePositions();
        var root = path.getCompilationUnit();
        var start = -1;
        var end = -1;
        for (var current = path; current != null; current = current.getParentPath()) {
            start = (int) pos.getStartPosition(current.getCompilationUnit(), current.getLeaf());
            end = (int) pos.getEndPosition(current.getCompilationUnit(), current.getLeaf());
            if (start >= 0 && end >= start) {
                break;
            }
        }
        if (!name.isEmpty() && start >= 0 && end >= start) {
            var namedStart = FindHelper.findNameIn(path.getCompilationUnit(), name, start, end);
            if (namedStart >= 0) {
                start = namedStart;
                end = start + name.length();
            } else if (strictNameMatch) {
                return null;
            }
        }
        if (start < 0 || end < start) {
            return null;
        }
        Range range;
        try {
            range = FileStore.range(root.getSourceFile().getCharContent(true).toString(), start, end);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var uri = normalizeUri(root.getSourceFile().toUri());
        return new Location(uri, range);
    }

    public static URI normalizeUri(URI uri) {
        if (uri == null) return null;
        if (!"jar".equals(uri.getScheme())) return uri;
        var cached = jarUriCache.get(uri);
        if (cached != null) {
            CacheAudit.hit("jar_source.extract");
            return cached;
        }
        CacheAudit.miss("jar_source.extract");
        var extracted = extractJarUri(uri);
        jarUriCache.put(uri, extracted);
        CacheAudit.load("jar_source.extract");
        CacheAudit.store("jar_source.extract");
        return extracted;
    }

    private static URI extractJarUri(URI uri) {
        try {
            URL url = uri.toURL();
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            String entryName = connection.getEntryName();
            URL jarFileUrl = connection.getJarFileURL();
            if (entryName == null || jarFileUrl == null) return uri;

            Path base = jarCacheDir();
            String jarKey = Integer.toHexString(jarFileUrl.toString().hashCode());
            Path out = base.resolve(jarKey).resolve(entryName);
            if (!Files.exists(out)) {
                Files.createDirectories(out.getParent());
                try (InputStream in = connection.getInputStream()) {
                    Files.copy(in, out);
                }
            }
            return out.toUri();
        } catch (IOException e) {
            return uri;
        }
    }

    private static Path jarCacheDir() throws IOException {
        if (jarCacheDir != null) return jarCacheDir;
        Path base = Paths.get(System.getProperty("java.io.tmpdir")).resolve("jls-jar-sources");
        Files.createDirectories(base);
        jarCacheDir = base;
        return jarCacheDir;
    }

    public static int findNameIn(CompilationUnitTree root, CharSequence name, int start, int end) {
        return findNameIn(root, name, start, end, -1);
    }

    /**
     * Like {@link #findNameIn(CompilationUnitTree, CharSequence, int, int)} but finds
     * the occurrence of {@code name} within {@code [start, end)} that contains
     * {@code cursor}. Falls back to the first occurrence if none contains the cursor.
     * Callers without cursor context can pass {@code -1} to always return the first
     * occurrence.
     */
    public static int findNameIn(
            CompilationUnitTree root, CharSequence name, int start, int end, long cursor) {
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var escaped = Pattern.quote(name.toString());
        var pattern = Pattern.compile("\\b" + escaped + "\\b");
        var matcher = pattern.matcher(contents);
        matcher.region(start, end);
        int firstMatch = -1;
        while (matcher.find()) {
            var nameStart = matcher.start();
            if (firstMatch < 0) firstMatch = nameStart;
            var nameEnd = matcher.end();
            if (nameStart <= cursor && cursor <= nameEnd) {
                return nameStart;
            }
        }
        return firstMatch;
    }
}
