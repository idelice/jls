package org.javacs.navigation;

import com.sun.source.util.Trees;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaFileObject;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.JarFileHelper;
import org.javacs.SourceFileObject;
import org.javacs.FileStore;
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

    public static DefinitionProvider fromUri(CompilerProvider compiler, URI uri, int line, int column) {
        var materialized = JarFileHelper.extractIfNeeded(uri);
        var path = Paths.get(materialized);
        return new DefinitionProvider(compiler, path, line, column);
    }

    public List<Location> find() {
        var tStart = System.nanoTime();
        long compileMs = 0;
        long resolveMs = 0;
        long remoteCompileMs = 0;
        String kind = "unknown";
        List<Location> result = NOT_SUPPORTED;

        var compileStart = System.nanoTime();
        try (var task = compiler.compile(file)) {
            compileMs = (System.nanoTime() - compileStart) / 1_000_000;
            var resolveStart = System.nanoTime();
            var element = NavigationHelper.findElement(task, file, line, column);
            resolveMs = (System.nanoTime() - resolveStart) / 1_000_000;
            if (element == null) {
                kind = "no-element";
                result = resolveByCursorWord().orElse(NOT_SUPPORTED);
            } else if (element.asType().getKind() == TypeKind.ERROR) {
                kind = "error";
                result = findDefinitions(task, element);
            } else if (NavigationHelper.isLocal(element)) {
                kind = "local";
                result = findDefinitions(task, element);
            } else {
                var className = className(element);
                if (className.isEmpty()) {
                    kind = "no-class";
                    result = NOT_SUPPORTED;
                } else {
                    var otherFile = resolveTargetSource(className);
                    if (otherFile.isEmpty()) {
                        kind = "not-found";
                        result = List.of();
                    } else if (otherFile.get().toUri().equals(file.toUri())) {
                        kind = "same-file";
                        result = findDefinitions(task, element);
                    } else {
                        task.close();
                        var remote = findRemoteDefinitions(otherFile.get(), className);
                        remoteCompileMs = remote.compileMs;
                        result = remote.locations;
                        kind = "remote";
                    }
                }
            }
        } finally {
            var totalMs = (System.nanoTime() - tStart) / 1_000_000;
            var size = result == null ? 0 : result.size();
            LOG.info(
                    String.format(
                            "Definition %s:%d:%d kind=%s total=%dms compile=%dms resolve=%dms remoteCompile=%dms results=%d",
                            file.getFileName(),
                            line,
                            column,
                            kind,
                            totalMs,
                            compileMs,
                            resolveMs,
                            remoteCompileMs,
                            size));
        }
        return result;
    }

    private String className(Element element) {
        if (element == null) return "";
        if (element instanceof TypeElement) {
            return ((TypeElement) element).getQualifiedName().toString();
        }
        return className(element.getEnclosingElement());
    }

    private DefinitionResult findRemoteDefinitions(JavaFileObject otherFile, String className) {
        var t0 = System.nanoTime();
        List<Location> locations;
        boolean needsFallback = false;
        long compileMs;
        var jdkSource = JarFileHelper.isJdkSource(otherFile.toUri());
        try (var task = compiler.compile(List.of(new SourceFileObject(file), otherFile))) {
            var element = NavigationHelper.findElement(task, file, line, column);
            locations = findDefinitions(task, element);
            needsFallback = locations.isEmpty();
            compileMs = (System.nanoTime() - t0) / 1_000_000;
        }

        if (needsFallback && (otherFile.getKind() != JavaFileObject.Kind.SOURCE || jdkSource)) {
            var fallbackStart = System.nanoTime();
            locations = fallbackFindInOtherFile(otherFile, className);
            compileMs += (System.nanoTime() - fallbackStart) / 1_000_000;
        }

        return new DefinitionResult(locations, compileMs);
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

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger("main");

    private static final class DefinitionResult {
        final List<Location> locations;
        final long compileMs;

        DefinitionResult(List<Location> locations, long compileMs) {
            this.locations = locations;
            this.compileMs = compileMs;
        }
    }

    private List<Location> resolveByName(String simpleName) {
        var pkg = packageNameFromSource(file).orElse("");
        var className = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        var target = resolveTargetSource(className);
        if (target.isEmpty()) {
            return List.of();
        }
        var remote = findRemoteDefinitions(target.get(), className);
        return remote.locations;
    }

    private Optional<JavaFileObject> resolveTargetSource(String className) {
        var target = findExternalSource(className).or(() -> compiler.findAnywhere(className));
        if (target.isEmpty()) return Optional.empty();
        return Optional.of(JarFileHelper.materialize(target.get()));
    }

    private Optional<String> packageNameFromSource(Path path) {
        var contents = FileStore.contents(path);
        if (contents == null) return Optional.empty();
        var matcher = PACKAGE_LINE.matcher(contents);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }

    private List<Location> fallbackFindInOtherFile(JavaFileObject otherFile, String className) {
        try (var task = compiler.compile(List.of(otherFile))) {
            var elements = task.task.getElements();
            var type = elements.getTypeElement(className);
            if (type == null) {
                return List.of();
            }
            var trees = Trees.instance(task.task);
            var path = trees.getPath(type);
            if (path == null) {
                return List.of();
            }
            var name = type.getSimpleName();
            return List.of(FindHelper.location(task, path, name));
        }
    }

    private Optional<List<Location>> resolveByCursorWord() {
        var word = wordAtCursor();
        if (word.isEmpty()) return Optional.empty();
        return Optional.of(resolveByName(word.get()));
    }

    private Optional<String> wordAtCursor() {
        if (line < 1) return Optional.empty();
        var contents = FileStore.contents(file);
        if (contents == null) return Optional.empty();
        var lines = contents.split("\\R", -1);
        if (line > lines.length) return Optional.empty();
        var text = lines[line - 1];
        if (text == null) return Optional.empty();
        if (column < 1 || column > text.length() + 1) return Optional.empty();
        int idx = Math.max(0, column - 1);
        return IDENTIFIER
                .matcher(text)
                .results()
                .filter(r -> r.start() <= idx && idx <= r.end())
                .findFirst()
                .map(r -> text.substring(r.start(), r.end()));
    }

    private Optional<JavaFileObject> findExternalSource(String className) {
        if (!isJarDerived(file)) {
            return Optional.empty();
        }
        var pkg = packageName(className);
        var topLevel = topLevelName(className, pkg);
        var relative =
                pkg.isEmpty()
                        ? Paths.get(topLevel + ".java")
                        : Paths.get(pkg.replace('.', '/')).resolve(topLevel + ".java");

        var jarRoot = externalJarRoot(file);
        if (jarRoot != null) {
            var candidate = jarRoot.resolve(relative);
            if (Files.exists(candidate)) {
                return Optional.of(new SourceFileObject(candidate));
            }
        }

        return Optional.empty();
    }

    private boolean isJarDerived(Path path) {
        var uri = path.toUri().toString();
        if (FileStore.isExternalUri(uri)) {
            return true;
        }
        // FileStore intentionally whitelists extracted jar sources under jls-sources as “internal”.
        // For navigation we still want jar-aware behavior, so detect that temp cache directly.
        return path.toString().replace('\\', '/').contains("/jls-sources/");
    }

    private Path externalJarRoot(Path path) {
        var normalized = path.toString().replace('\\', '/');
        var marker = "/jls-sources/";
        var idx = normalized.indexOf(marker);
        if (idx == -1) return null;
        var next = normalized.indexOf('/', idx + marker.length());
        if (next == -1) return null;
        return Paths.get(normalized.substring(0, next));
    }

    private static final Pattern PACKAGE_EXTRACTOR =
            Pattern.compile("^([a-z][_a-zA-Z0-9]*\\.)*[a-z][_a-zA-Z0-9]*");
    private static final Pattern PACKAGE_LINE =
            Pattern.compile("(?m)^\\s*package\\s+([^;\\s]+)\\s*;");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_$][A-Za-z\\d_$]*");

    private String packageName(String className) {
        var m = PACKAGE_EXTRACTOR.matcher(className);
        if (m.find()) {
            return m.group();
        }
        return "";
    }

    private String topLevelName(String className, String pkg) {
        var start = pkg.isEmpty() ? 0 : pkg.length() + 1;
        if (start >= className.length()) {
            return "";
        }
        var remainder = className.substring(start).replace('$', '.');
        var dot = remainder.indexOf('.');
        return dot == -1 ? remainder : remainder.substring(0, dot);
    }
}
