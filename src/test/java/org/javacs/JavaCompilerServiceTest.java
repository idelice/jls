package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.tools.JavaFileObject;
import org.junit.*;

public class JavaCompilerServiceTest {
    static {
        Main.setRootFormat();
    }

    private JavaCompilerService compiler =
            new JavaCompilerService(
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptySet());

    @Test
    public void lombokCanBeDisabledBySetting() {
        var classPath = Set.of(Paths.get("lib/lombok-1.18.30.jar"));
        var service =
                new JavaCompilerService(
                        classPath,
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        false);
        assertThat(service.lombokPresentOnClasspath, is(true));
        assertThat(service.lombokConfiguredEnabled, is(false));
    }

    @Test
    public void lombokDefaultsToEnabledWhenPresentOnClasspath() {
        var classPath = Set.of(Paths.get("lib/lombok-1.18.30.jar"));
        var service =
                new JavaCompilerService(
                        classPath, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        assertThat(service.lombokPresentOnClasspath, is(true));
        assertThat(service.lombokConfiguredEnabled, is(true));
    }

    static Path simpleProjectSrc() {
        return Paths.get("src/test/examples/simple-project").normalize();
    }

    @Before
    public void setWorkspaceRoot() {
        FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
    }

    @Test
    public void compileCacheUsesSourceVersionWhenModifiedMillisIsUnchanged() {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var initial = FileStore.contents(file);
        var updated = initial.replace("Hello world!", "Hello world 2!");
        var fixedTime = Instant.EPOCH;

        try (var first = compiler.compileFast(List.of(new SourceFileObject(file, initial, fixedTime, 1)))) {
            assertThat(first.root().toString(), containsString("Hello world!"));
        }
        try (var second = compiler.compileFast(List.of(new SourceFileObject(file, updated, fixedTime, 2)))) {
            assertThat(second.root().toString(), containsString("Hello world 2!"));
        }
    }

    @Test
    public void parseCacheReusesAstUntilSourceVersionChanges() throws Exception {
        var file = Files.createTempFile("parse-cache-", ".java");
        try {
            var first = compiler.parse(new SourceFileObject(file, "class ParseCache { int one; }\n", Instant.EPOCH, 1));
            var second = compiler.parse(new SourceFileObject(file, "class ParseCache { int one; }\n", Instant.EPOCH, 1));
            assertThat("same source fingerprint should reuse cached AST", second.root, sameInstance(first.root));

            var third = compiler.parse(new SourceFileObject(file, "class ParseCache { int two; }\n", Instant.EPOCH, 2));
            assertThat("new source version should refresh cached AST", third.root, not(sameInstance(first.root)));
            var parsed = compiler.parsedUnits.get(file);
            assertThat("parsedUnits should track latest parsed unit per file", parsed, notNullValue());
            assertThat("parsedUnits should track latest AST per file", parsed.task.root, sameInstance(third.root));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void parseCacheIsSharedAcrossCompilerServicesForSameSourceVersion() throws Exception {
        var file = Files.createTempFile("shared-parse-cache-", ".java");
        try {
            var firstCompiler =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());
            var secondCompiler =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());

            var first =
                    firstCompiler.parse(
                            new SourceFileObject(file, "class SharedParseCache { int one; }\n", Instant.EPOCH, 1));
            var second =
                    secondCompiler.parse(
                            new SourceFileObject(file, "class SharedParseCache { int one; }\n", Instant.EPOCH, 1));

            assertThat(
                    "same source fingerprint parsed by different compilers should reuse shared AST",
                    second.root,
                    sameInstance(first.root));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void reusesPerOptionCompilerContextsWithoutRepeatedRecreation() throws Exception {
        var service =
                new JavaCompilerService(
                        Set.of(Paths.get("lib/lombok-1.18.30.jar")),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());
        var file = FindResource.path("org/javacs/example/HelloWorld.java");

        try (var ignored = service.compile(file)) {}
        try (var ignored = service.compileFastWithProcessors(file)) {}
        try (var ignored = service.compile(file)) {}
        try (var ignored = service.compileFastWithProcessors(file)) {}

        var contexts = reusableCompilerContexts(service.compiler);
        assertThat("expected one context per distinct option set", contexts.size(), is(2));
    }

    @Test
    public void quickLombokGateUsesStrictAnnotationDetection() throws Exception {
        var lombokDataFile = Files.createTempFile("quick-lombok-data-", ".java");
        var springServiceFile = Files.createTempFile("quick-lombok-service-", ".java");
        try {
            Files.writeString(
                    lombokDataFile,
                    "import lombok.Data;\n@Data\nclass QuickData { String value; }\n");
            Files.writeString(
                    springServiceFile,
                    "import org.springframework.stereotype.Service;\n@Service\nclass QuickService {}\n");

            assertThat(
                    "@Data should enable Lombok source expansion",
                    quickMaybeUsesLombok(compiler, lombokDataFile),
                    is(true));
            assertThat(
                    "@Service should not enable Lombok source expansion",
                    quickMaybeUsesLombok(compiler, springServiceFile),
                    is(false));
        } finally {
            Files.deleteIfExists(lombokDataFile);
            Files.deleteIfExists(springServiceFile);
        }
    }

    @Test
    public void lombokConsumerFileExpandsOnlyReferencedLombokTypes() throws Exception {
        var root = Files.createTempDirectory("lombok-consumer-expand-");
        try {
            var pkg = root.resolve("p");
            Files.createDirectories(pkg);
            var consumer = pkg.resolve("ServiceTwo.java");
            var user = pkg.resolve("User.java");
            var other = pkg.resolve("Order.java");

            Files.writeString(
                    user,
                    "package p;\n"
                            + "import lombok.Data;\n"
                            + "@Data\n"
                            + "class User { String name; }\n");
            Files.writeString(
                    other,
                    "package p;\n"
                            + "import lombok.Data;\n"
                            + "@Data\n"
                            + "class Order { String id; }\n");
            Files.writeString(
                    consumer,
                    "package p;\n"
                            + "class ServiceTwo {\n"
                            + "  void test(User u) {\n"
                            + "    u.getName();\n"
                            + "  }\n"
                            + "}\n");

            FileStore.setWorkspaceRoots(Set.of(root));
            var service =
                    new JavaCompilerService(
                            Set.of(Paths.get("lib/lombok-1.18.30.jar")),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());

            var expanded = expandedSourcesForLombokAp(service, consumer);
            assertThat("consumer source should be included", expanded, hasItem(consumer));
            assertThat("referenced Lombok source should be included", expanded, hasItem(user));
            assertThat("unreferenced Lombok source should not be included", expanded, not(hasItem(other)));
            assertThat("compile set should remain minimal", expanded.size(), is(2));
        } finally {
            FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
            deleteTree(root);
        }
    }

    @Test
    public void nonLombokProjectDoesNotExpandSourcesForAp() throws Exception {
        var root = Files.createTempDirectory("lombok-no-expand-");
        try {
            var pkg = root.resolve("q");
            Files.createDirectories(pkg);
            var consumer = pkg.resolve("ServiceTwo.java");
            var user = pkg.resolve("User.java");

            Files.writeString(user, "package q;\nclass User { String name; }\n");
            Files.writeString(
                    consumer,
                    "package q;\n"
                            + "class ServiceTwo {\n"
                            + "  void test(User u) {\n"
                            + "    u.toString();\n"
                            + "  }\n"
                            + "}\n");

            FileStore.setWorkspaceRoots(Set.of(root));
            var service =
                    new JavaCompilerService(
                            Set.of(Paths.get("lib/lombok-1.18.30.jar")),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());

            var expanded = expandedSourcesForLombokAp(service, consumer);
            assertThat("non-Lombok project should not expand compile set", expanded.size(), is(1));
            assertThat(expanded, hasItem(consumer));
            assertThat(expanded, not(hasItem(user)));
        } finally {
            FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
            deleteTree(root);
        }
    }

    @Test
    public void fullWorkspaceCompileSkipsPackagePrivateRetryWhenWorkspaceAlreadyCovered() throws Exception {
        var root = Files.createTempDirectory("workspace-covered-compile-");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var pkg = root.resolve("p");
            Files.createDirectories(pkg);
            var use = pkg.resolve("UseHidden.java");
            var defs = pkg.resolve("Defs.java");
            Files.writeString(
                    use,
                    "package p;\n"
                            + "class UseHidden {\n"
                            + "  Hidden hidden = new Hidden();\n"
                            + "}\n");
            Files.writeString(defs, "package p;\nclass Hidden {}\n");

            FileStore.setWorkspaceRoots(Set.of(root));
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());

            try (var task = service.compile(use, defs)) {
                assertThat(
                        task.diagnostics.stream().noneMatch(d -> d.getCode().contains("cant.resolve.location")),
                        is(true));
            }

            assertThat(
                    "full workspace compile should not do a second compile batch",
                    capture.countContaining("compile_retry mode=full sources=2 additional=1 action=second_attempt"),
                    is(0));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
            deleteTree(root);
        }
    }

    @Test
    public void targetedFullCompileStillRetriesForPackagePrivateSourceRecovery() throws Exception {
        var root = Files.createTempDirectory("targeted-retry-compile-");
        var logger = Logger.getLogger("main");
        var previousLevel = logger.getLevel();
        logger.setLevel(Level.FINE);
        var capture = new TestLogCapture();
        logger.addHandler(capture);
        try {
            var pkg = root.resolve("p");
            Files.createDirectories(pkg);
            var use = pkg.resolve("UseHidden.java");
            var defs = pkg.resolve("Defs.java");
            Files.writeString(
                    use,
                    "package p;\n"
                            + "class UseHidden {\n"
                            + "  Hidden hidden = new Hidden();\n"
                            + "}\n");
            Files.writeString(defs, "package p;\nclass Hidden {}\n");

            FileStore.setWorkspaceRoots(Set.of(root));
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());

            try (var task = service.compile(use)) {
                assertThat(
                        "targeted full compile should recover the package-private dependency",
                        task.diagnostics.stream().noneMatch(d -> d.getCode().contains("cant.resolve.location")),
                        is(true));
            }

            assertThat(
                    "targeted compile should still run a second batch when additional sources are needed",
                    capture.countContaining("compile_retry mode=full sources=1 additional=1 action=second_attempt"),
                    greaterThan(0));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
            deleteTree(root);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<List<String>, ?> reusableCompilerContexts(ReusableCompiler compiler) throws Exception {
        Field field = ReusableCompiler.class.getDeclaredField("contexts");
        field.setAccessible(true);
        return (Map<List<String>, ?>) field.get(compiler);
    }

    private static boolean quickMaybeUsesLombok(JavaCompilerService service, Path file) throws Exception {
        Method method = JavaCompilerService.class.getDeclaredMethod("quickMaybeUsesLombok", Path.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, file);
    }

    @SuppressWarnings("unchecked")
    private static List<Path> expandedSourcesForLombokAp(JavaCompilerService service, Path file) throws Exception {
        Method method =
                JavaCompilerService.class.getDeclaredMethod(
                        "expandSourcesForLombokAP", Collection.class, boolean.class);
        method.setAccessible(true);
        var sources = List.of((JavaFileObject) new SourceFileObject(file));
        var expanded = (Collection<? extends JavaFileObject>) method.invoke(service, sources, true);
        var paths = new ArrayList<Path>();
        for (var source : expanded) {
            paths.add(Paths.get(source.toUri()));
        }
        return paths;
    }

    private static void deleteTree(Path root) throws Exception {
        Files.walkFileTree(
                root,
                new FileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        throw exc;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc != null) throw exc;
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    private static final class TestLogCapture extends Handler {
        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(LogRecord record) {
            lines.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        int countContaining(String needle) {
            synchronized (lines) {
                var count = 0;
                for (var line : lines) {
                    if (line != null && line.contains(needle)) {
                        count++;
                    }
                }
                return count;
            }
        }
    }
}
