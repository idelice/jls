package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
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

    @SuppressWarnings("unchecked")
    private static Map<List<String>, ?> reusableCompilerContexts(ReusableCompiler compiler) throws Exception {
        Field field = ReusableCompiler.class.getDeclaredField("contexts");
        field.setAccessible(true);
        return (Map<List<String>, ?>) field.get(compiler);
    }
}
