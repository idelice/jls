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
import org.javacs.lsp.Location;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

public class FindHelper {
    private static final Map<URI, URI> jarUriCache = new ConcurrentHashMap<>();
    private static Path jarCacheDir;

    public static String[] erasedParameterTypes(CompileTask task, ExecutableElement method) {
        var types = task.task.getTypes();
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
        return new FindTypeDeclarationNamed().scan(task.root, className);
    }

    public static ExecutableElement findMethod(
            CompileTask task, String className, String methodName, String[] erasedParameterTypes) {
        var type = task.task.getElements().getTypeElement(className);
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
        var types = task.task.getTypes();
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
        if (candidate instanceof ParameterizedTypeTree) {
            var parameterized = (ParameterizedTypeTree) candidate;
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
        if (candidate instanceof ArrayTypeTree) {
            var array = (ArrayTypeTree) candidate;
            if (!erasedType.endsWith("[]")) return false;
            var erasedElement = erasedType.substring(0, erasedType.length() - "[]".length());
            return typeMatches(array.getType(), erasedElement);
        }
        return true;
    }

    public static Location location(CompileTask task, TreePath path) {
        return location(task, path, "");
    }

    public static Location location(CompileTask task, TreePath path, CharSequence name) {
        var lines = path.getCompilationUnit().getLineMap();
        var pos = Trees.instance(task.task).getSourcePositions();
        var start = (int) pos.getStartPosition(path.getCompilationUnit(), path.getLeaf());
        var end = (int) pos.getEndPosition(path.getCompilationUnit(), path.getLeaf());
        if (name.length() > 0) {
            start = FindHelper.findNameIn(path.getCompilationUnit(), name, start, end);
            end = start + name.length();
        }
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var startPos = new Position(startLine - 1, startColumn - 1);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        var endPos = new Position(endLine - 1, endColumn - 1);
        var range = new Range(startPos, endPos);
        var uri = normalizeUri(path.getCompilationUnit().getSourceFile().toUri());
        return new Location(uri, range);
    }

    private static URI normalizeUri(URI uri) {
        if (uri == null) return null;
        if (!"jar".equals(uri.getScheme())) return uri;
        return jarUriCache.computeIfAbsent(uri, FindHelper::extractJarUri);
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
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var matcher = Pattern.compile("\\b" + name + "\\b").matcher(contents);
        matcher.region(start, end);
        if (matcher.find()) {
            return matcher.start();
        }
        return -1;
    }
}
