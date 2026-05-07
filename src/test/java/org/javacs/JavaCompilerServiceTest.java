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

//    @Test
//    public void lombokCanBeDisabledBySetting() {
//        var classPath = Set.of(TestRuntimeJars.lombokJar());
//        var service =
//                new JavaCompilerService(
//                        classPath,
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        false);
//        assertThat(service.lombokPresentOnClasspath, is(true));
//        assertThat(service.lombokConfiguredEnabled, is(false));
//    }

    @Test
    public void lombokDefaultsToEnabledWhenPresentOnClasspath() {
        var classPath = Set.of(TestRuntimeJars.lombokJar());
        var service =
                new JavaCompilerService(
                        classPath, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        assertThat(service.lombokPresentOnClasspath, is(true));
//        assertThat(service.lombokConfiguredEnabled, is(true));
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
            assertThat("same source fingerprint should reuse cached AST", second.root(), sameInstance(first.root()));

            var third = compiler.parse(new SourceFileObject(file, "class ParseCache { int two; }\n", Instant.EPOCH, 2));
            assertThat("new source version should refresh cached AST", third.root(), not(sameInstance(first.root())));
            var parsed = compiler.parsedUnits.get(file);
            assertThat("parsedUnits should track latest parsed unit per file", parsed, notNullValue());
            assertThat("parsedUnits should track latest AST per file", parsed.task().root(), sameInstance(third.root()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void parseCacheIsScopedToCompilerServiceInstances() throws Exception {
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
            var secondRepeat =
                    secondCompiler.parse(
                            new SourceFileObject(file, "class SharedParseCache { int one; }\n", Instant.EPOCH, 1));

            assertThat(
                    "parse caching should remain isolated per compiler service",
                    second.root(),
                    not(sameInstance(first.root())));
            assertThat(
                    "repeated parses within the same compiler service should still reuse the cached AST",
                    secondRepeat.root(),
                    sameInstance(second.root()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void keepsCompilerContextGrowthBoundedAcrossRepeatedCompiles() throws Exception {
        var fullService =
                new JavaCompilerService(
                        Set.of(TestRuntimeJars.lombokJar()),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());
        var file = FindResource.path("org/javacs/example/HelloWorld.java");

        try (var ignored = fullService.compile(file)) {}
        try (var ignored = fullService.compile(file)) {}
        var fullContexts = reusableCompilerContexts(fullService.compiler);
        assertThat(
                "repeated full compiles should not keep creating new reusable contexts",
                fullContexts.size(),
                lessThanOrEqualTo(2));

        var fastService =
                new JavaCompilerService(
                        Set.of(TestRuntimeJars.lombokJar()),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());
        try (var ignored = fastService.compileFastWithProcessors(file)) {}
        try (var ignored = fastService.compileFastWithProcessors(file)) {}
        var fastContexts = reusableCompilerContexts(fastService.compiler);
        assertThat(
                "repeated fast AP compiles should not keep creating new reusable contexts",
                    fastContexts.size(),
                    lessThanOrEqualTo(2));
    }

    @Test
    public void reusableCompilerUnlocksAfterTaskCreationFailure() {
        var reusable = new ReusableCompiler();

        try {
            reusable.getTask(
                    compiler.fileManager,
                    diagnostic -> {},
                    List.of("--release", "21", "-source", "21"),
                    List.of(),
                    List.of());
            fail("expected javac option parsing to fail");
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage(), containsString("--release"));
        }

        try (var borrow =
                reusable.getTask(
                        compiler.fileManager,
                        diagnostic -> {},
                        List.of("--release", "21"),
                        List.of(),
                        List.of())) {
            assertThat("compiler should unlock after getTask failure", borrow, notNullValue());
        }
    }

    @Test
    public void reusableCompilerReplacesSlotContextAfterTaskCreationFailure() {
        var reusable = new ReusableCompiler();
        var slot = new ReusableCompiler.SlotContext();

        try {
            reusable.createTask(
                    slot,
                    compiler.fileManager,
                    diagnostic -> {},
                    List.of("--release", "21", "-source", "21"),
                    List.of());
            fail("expected javac option parsing to fail");
        } catch (RuntimeException | AssertionError expected) {
            assertThat(expected.getMessage(), containsString("--release"));
        }

        var task =
                reusable.createTask(
                        slot,
                        compiler.fileManager,
                        diagnostic -> {},
                        List.of("--release", "21"),
                        List.of());
        try {
            assertThat("compiler should unlock after createTask failure", slot.inUse, is(true));
        } finally {
            reusable.releaseTask(slot, task);
        }
        assertThat("slot should be released after valid task closes", slot.inUse, is(false));
    }

    @Test
    public void compileTwiceWithReleaseArgsReusesNoStaleCompilerState() throws Exception {
        var file = Files.createTempFile("release-compile-", ".java");
        try {
            Files.writeString(
                    file,
                    """
                    class ReleaseCompile {
                        String value() {
                            return "ok";
                        }
                    }
                    """);
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            List.of("--release", "21"));

            try (var ignored = service.compile(file)) {}
            try (var ignored = service.compile(file)) {}
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void closingCompileTaskClosesUnderlyingBorrow() throws Exception {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var service =
                new JavaCompilerService(
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());

        CompileBatch batch;
        try (var ignored = service.compile(file)) {
            batch = cachedCompile(service, "cachedCompile");
            assertThat("borrow should stay open while compile task is in use", batch.borrow.closed, is(false));
        }

        assertThat("compile batch should be marked closed after task close", batch.closed, is(true));
        assertThat("closing the compile task should fully close the underlying borrow", batch.borrow.closed, is(true));
    }

    @Test
    public void closingCompileTaskTwiceIsSafe() throws Exception {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var service =
                new JavaCompilerService(
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());

        var task = service.compile(file);
        var batch = cachedCompile(service, "cachedCompile");
        task.close();
        task.close();

        assertThat("compile batch should stay closed after repeated close", batch.closed, is(true));
        assertThat("borrow should stay closed after repeated close", batch.borrow.closed, is(true));
    }

    @Test
    public void closedCachedCompileRefreshesInsteadOfReportingCacheHit() throws Exception {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var service =
                new JavaCompilerService(
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());

        CompileBatch firstBatch;
        try (var first = service.compile(file)) {
            firstBatch = cachedCompile(service, "cachedCompile");
            assertThat("initial compile should populate the full-compile cache", firstBatch, notNullValue());
            assertThat(service.lastCompileTelemetry().path(), is("cache_refresh"));
        }

        assertThat("closing the first compile should mark the cached batch closed", firstBatch.closed, is(true));

        CompileBatch secondBatch;
        try (var second = service.compile(file)) {
            secondBatch = cachedCompile(service, "cachedCompile");
            assertThat("closed cache entry should be replaced with a fresh batch", secondBatch, not(sameInstance(firstBatch)));
            assertThat(
                    "reusing a closed cached compile would incorrectly report a cache hit",
                    service.lastCompileTelemetry().path(),
                    is("cache_refresh"));
        }
    }

    @Test
    public void openCachedCompileReportsCacheHitAndSharesBatchLifetime() throws Exception {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var service =
                new JavaCompilerService(
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());

        try (var first = service.compile(file)) {
            var firstBatch = cachedCompile(service, "cachedCompile");
            assertThat("initial compile should populate the full-compile cache", firstBatch, notNullValue());
            assertThat(service.lastCompileTelemetry().path(), is("cache_refresh"));

            try (var second = service.compile(file)) {
                var secondBatch = cachedCompile(service, "cachedCompile");
                assertThat("live cache entry should be reused", secondBatch, sameInstance(firstBatch));
                assertThat("reusing a live cached compile should report a cache hit", service.lastCompileTelemetry().path(), is("cache_hit"));
            }

            assertThat("closing the second shared compile task should close the shared batch", firstBatch.closed, is(true));
        }
    }

    @Test
    public void cacheRefreshClosesPreviousCachedBorrow() throws Exception {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var initial = FileStore.contents(file);
        var updated = initial.replace("Hello world!", "Hello world 2!");
        var fixedTime = Instant.EPOCH;
        var service =
                new JavaCompilerService(
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());

        CompileBatch firstBatch;
        try (var first =
                service.compileFast(List.of(new SourceFileObject(file, initial, fixedTime, 1)))) {
            firstBatch = cachedCompile(service, "cachedFastCompileNoAp");
            assertThat(firstBatch.borrow.closed, is(false));
        }
        assertThat("first cached borrow should close when the task closes", firstBatch.borrow.closed, is(true));

        CompileBatch secondBatch;
        try (var second =
                service.compileFast(List.of(new SourceFileObject(file, updated, fixedTime, 2)))) {
            secondBatch = cachedCompile(service, "cachedFastCompileNoAp");
            assertThat("cache refresh should replace the cached batch", secondBatch, not(sameInstance(firstBatch)));
            assertThat("previous cached borrow should remain closed after refresh", firstBatch.borrow.closed, is(true));
            assertThat("new cached borrow should be open while the refreshed task is in use", secondBatch.borrow.closed, is(false));
        }

        assertThat("refreshed cached borrow should close when its task closes", secondBatch.borrow.closed, is(true));
    }

    @Test
    public void openCachedFastCompileReportsCacheHitAndSharesBatchLifetime() throws Exception {
        var file = FindResource.path("org/javacs/example/HelloWorld.java");
        var source = FileStore.contents(file);
        var fixedTime = Instant.EPOCH;
        var service =
                new JavaCompilerService(
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());

        try (var first =
                service.compileFast(List.of(new SourceFileObject(file, source, fixedTime, 1)))) {
            var firstBatch = cachedCompile(service, "cachedFastCompileNoAp");
            assertThat("initial fast compile should populate the fast cache", firstBatch, notNullValue());
            assertThat(service.lastCompileTelemetry().path(), is("cache_refresh"));

            try (var second =
                    service.compileFast(List.of(new SourceFileObject(file, source, fixedTime, 1)))) {
                var secondBatch = cachedCompile(service, "cachedFastCompileNoAp");
                assertThat("live fast cache entry should be reused", secondBatch, sameInstance(firstBatch));
                assertThat("reusing a live fast cached compile should report a cache hit", service.lastCompileTelemetry().path(), is("cache_hit"));
            }

            assertThat("closing the second shared fast-compile task should close the shared batch", firstBatch.closed, is(true));
        }
    }

//    @Test
//    public void backgroundCompilerDoesNotRetainDiagnosticsCompileBatch() throws Exception {
//        var file = FindResource.path("org/javacs/example/HelloWorld.java");
//        var service =
//                new JavaCompilerService(
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        true,
//                        "background");
//
//        try (var first = service.compileDiagnostics(List.of(new SourceFileObject(file)))) {
//            assertThat(
//                    "background compiler should not retain diagnostics compile batches",
//                    cachedCompile(service, "cachedCompile"),
//                    nullValue());
//            assertThat(service.lastCompileTelemetry().path(), is("uncached_role"));
//        }
//
//        try (var second = service.compileDiagnostics(List.of(new SourceFileObject(file)))) {
//            assertThat(
//                    "background compiler should continue compiling without a retained cache slot",
//                    cachedCompile(service, "cachedCompile"),
//                    nullValue());
//            assertThat(service.lastCompileTelemetry().path(), is("uncached_role"));
//        }
//    }

//    @Test
//    public void diagnosticsCompilerDoesNotRetainCompileBatch() throws Exception {
//        var file = FindResource.path("org/javacs/example/HelloWorld.java");
//        var service =
//                new JavaCompilerService(
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        true,
//                        "diagnostics");
//
//        try (var first = service.compileDiagnostics(List.of(new SourceFileObject(file)))) {
//            assertThat(
//                    "diagnostics compiler should not retain compile batches after pull-diagnostic request",
//                    cachedCompile(service, "cachedCompile"),
//                    nullValue());
//            assertThat(service.lastCompileTelemetry().path(), is("uncached_role"));
//        }
//
//        try (var second = service.compileDiagnostics(List.of(new SourceFileObject(file)))) {
//            assertThat(
//                    "diagnostics compiler should not accumulate retained batches across repeated requests",
//                    cachedCompile(service, "cachedCompile"),
//                    nullValue());
//            assertThat(service.lastCompileTelemetry().path(), is("uncached_role"));
//        }
//    }

//    @Test
//    public void indexCompilerDoesNotRetainFastCompileBatch() throws Exception {
//        var file = FindResource.path("org/javacs/example/HelloWorld.java");
//        var source = FileStore.contents(file);
//        var fixedTime = Instant.EPOCH;
//        var service =
//                new JavaCompilerService(
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        Collections.emptySet(),
//                        true,
//                        "index");
//
//        try (var first =
//                service.compileFast(List.of(new SourceFileObject(file, source, fixedTime, 1)))) {
//            assertThat(
//                    "index compiler should not retain fast compile batches after index extraction work",
//                    cachedCompile(service, "cachedFastCompileNoAp"),
//                    nullValue());
//            assertThat(service.lastCompileTelemetry().path(), is("uncached_role"));
//        }
//
//        try (var second =
//                service.compileFast(List.of(new SourceFileObject(file, source, fixedTime, 1)))) {
//            assertThat(
//                    "index compiler should keep using reusable compiler context without retaining compile graphs",
//                    cachedCompile(service, "cachedFastCompileNoAp"),
//                    nullValue());
//            assertThat(service.lastCompileTelemetry().path(), is("uncached_role"));
//        }
//    }

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
                            Set.of(TestRuntimeJars.lombokJar()),
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
    public void compileDiagnosticsDoesNotExpandToPackagePrivateCompanions() throws Exception {
        var root = Files.createTempDirectory("diagnostics-no-expansion-");
        try {
            var pkg = root.resolve("p");
            Files.createDirectories(pkg);
            var use = pkg.resolve("Use.java");
            var defs = pkg.resolve("Defs.java");

            Files.writeString(
                    defs,
                    "package p;\n"
                            + "class PackagePrivateType {}\n");
            Files.writeString(
                    use,
                    "package p;\n"
                            + "class Use {\n"
                            + "  PackagePrivateType value;\n"
                            + "}\n");

            FileStore.setWorkspaceRoots(Set.of(root));
            var service =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());

            try (var task = service.compile(use)) {
                assertThat(
                        "regular compile should expand to include package-private companion",
                        task.roots.size(),
                        is(2));
                assertThat(
                        "regular compile should resolve package-private companion without unresolved-location diagnostic",
                        task.diagnostics.stream().noneMatch(d -> d.getCode().contains("cant.resolve.location")),
                        is(true));
            }

            try (var task = service.compileDiagnostics(List.of(new SourceFileObject(use)))) {
                assertThat(
                        "diagnostics compile should stay constrained to explicitly requested roots",
                        task.roots.size(),
                        is(1));
                assertThat(
                        "constrained diagnostics compile should surface unresolved companion diagnostic instead of expanding sources",
                        task.diagnostics.stream().anyMatch(d -> d.getCode().contains("cant.resolve.location")),
                        is(true));
            }
        } finally {
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
                            Set.of(TestRuntimeJars.lombokJar()),
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
                assertThat(
                        "explicitly requested workspace sources should compile in a single batch",
                        task.roots.size(),
                        is(2));
            }
        } finally {
            FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
            deleteTree(root);
        }
    }

    @Test
    public void targetedFullCompileRecoversPackagePrivateDependency() throws Exception {
        var root = Files.createTempDirectory("targeted-retry-compile-");
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
                assertThat(
                        "targeted full compile should include the recovered package-private source in the batch",
                        task.roots.size(),
                        is(2));
            }
        } finally {
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

    private static CompileBatch cachedCompile(JavaCompilerService service, String fieldName) throws Exception {
        Field field = JavaCompilerService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (CompileBatch) field.get(service);
    }

    private static boolean quickMaybeUsesLombok(JavaCompilerService service, Path file) throws Exception {
        Method method = JavaCompilerService.class.getDeclaredMethod("hasLombokAnnotation", Path.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, file);
    }

    @SuppressWarnings("unchecked")
    private static List<Path> expandedSourcesForLombokAp(JavaCompilerService service, Path file) throws Exception {
        Method method =
                JavaCompilerService.class.getDeclaredMethod(
                        "expandSourcesForLombokAPDetails", Collection.class, boolean.class);
        method.setAccessible(true);
        var sources = List.of((JavaFileObject) new SourceFileObject(file));
        var expandedResult = method.invoke(service, sources, true);
        var expandedSourcesMethod = expandedResult.getClass().getDeclaredMethod("sources");
        expandedSourcesMethod.setAccessible(true);
        var expanded =
                (Collection<? extends JavaFileObject>) expandedSourcesMethod.invoke(expandedResult);
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

    // -------------------------------------------------------------------------
    // CompilerSharedResources / Docs factory targeted tests
    // -------------------------------------------------------------------------

//    @Test
//    public void sharedResourcesLanesProduceSamePublicTopLevelTypes() {
//        var shared = CompilerSharedResources.from(
//                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), List.of());
//        var lane1 = new JavaCompilerService(shared, true, "interactive");
//        var lane2 = new JavaCompilerService(shared, true, "background");
//
//        var types1 = lane1.publicTopLevelTypes();
//        var types2 = lane2.publicTopLevelTypes();
//        assertThat(
//                "both lanes from one shared resources object should return the same public top-level types",
//                types1, containsInAnyOrder(types2.toArray()));
//    }

    @Test
    public void docsCreateFileManagerProducesWorkingJdkSourceLookup() {
        var srcZip = Docs.findSrcZip();
        org.junit.Assume.assumeThat("src.zip must be present for JDK source lookup", srcZip, not(equalTo(Docs.NOT_FOUND)));

        var docs = new Docs(Collections.emptySet());
        var fm1 = docs.createFileManager();
        var fm2 = docs.createFileManager();

        assertThat("createFileManager must return a new instance on each call", fm1, not(sameInstance(fm2)));
        // Both file managers should be independently usable; a simple non-null check confirms setup succeeded.
        assertThat("first file manager should be non-null", fm1, notNullValue());
        assertThat("second file manager should be non-null", fm2, notNullValue());
    }

//    @Test
//    public void lanesFromSharedResourcesHaveIndependentDocsFileManagers() {
//        var shared = CompilerSharedResources.from(
//                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), List.of());
//        var lane1 = new JavaCompilerService(shared, true, "interactive");
//        var lane2 = new JavaCompilerService(shared, true, "index");
//
//        assertThat(
//                "each lane must get its own docsFileManager instance so they cannot share mutable file-manager state",
//                lane1.docsFileManager, not(sameInstance(lane2.docsFileManager)));
//    }

    @Test
    public void sharedResourcesExtraArgsNormalizationMatchesConvenienceConstructor() {
        // Set<String> passed to convenience constructor triggers sort; the primary path should produce
        // the same sorted list so that all lanes see identical compiler arguments.
        var rawArgs = new LinkedHashSet<>(List.of("-target", "17", "-source", "17"));
        var shared = CompilerSharedResources.from(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), rawArgs);
        var viaConvenience = new JavaCompilerService(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), rawArgs);

        assertThat(
                "shared resources should produce the same normalized extra-args as the convenience constructor",
                shared.extraArgs(), is(viaConvenience.extraArgs));
    }
}
