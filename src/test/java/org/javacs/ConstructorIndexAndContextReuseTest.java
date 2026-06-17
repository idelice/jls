package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.nio.file.*;
import java.util.*;
import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.index.TypeIndexRouter;
import org.javacs.lsp.CompletionItemKind;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.provider.InlayHintProvider;
import org.junit.*;

/**
 * Tests for:
 * 1) Constructor indexing (workspace + external) so inlay hints + signature work
 * 2) Context reuse — multiple compileFast calls don't fail with "duplicate context value"
 */
public class ConstructorIndexAndContextReuseTest {
    static {
        Main.setRootFormat();
    }

    private static Path workspaceRoot;

    @BeforeClass
    public static void setup() throws Exception {
        workspaceRoot = Files.createTempDirectory("ctor-index-test-");
        var pkgDir = workspaceRoot.resolve("pkg");
        Files.createDirectories(pkgDir);

        var recordFile = pkgDir.resolve("MyRecord.java");
        Files.writeString(recordFile,
                "package pkg;\nrecord MyRecord(String name, int age) {}\n");

        var classFile = pkgDir.resolve("MyClass.java");
        Files.writeString(classFile,
                "package pkg;\nclass MyClass {\n" +
                "    private String value;\n" +
                "    public MyClass(String value, int count) { this.value = value; }\n" +
                "}\n");

        var usageFile = pkgDir.resolve("Usage.java");
        Files.writeString(usageFile,
                "package pkg;\nclass Usage {\n" +
                "    void test() {\n" +
                "        var r = new MyRecord(\"hello\", 42);\n" +
                "        var c = new MyClass(\"world\", 7);\n" +
                "    }\n" +
                "}\n");

        FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
    }

    @AfterClass
    public static void teardown() throws Exception {
        FileStore.reset();
        if (workspaceRoot != null) {
            Files.walk(workspaceRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception e) {} });
        }
    }

    @Test
    public void workspaceIndexIncludesRecordCanonicalConstructor() {
        var compiler = new JavaCompilerService(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        var parseTasks = List.of(
                compiler.parse(workspaceRoot.resolve("pkg/MyRecord.java")),
                compiler.parse(workspaceRoot.resolve("pkg/MyClass.java")));
        var index = WorkspaceTypeIndex.fromParseTrees(parseTasks);
        var ctors = index.constructors("pkg.MyRecord");
        assertThat("record canonical constructor should be indexed", ctors, not(empty()));
        assertThat(ctors.get(0).parameterNames, is(new String[]{"name", "age"}));
        assertThat(ctors.get(0).kind, is(CompletionItemKind.Constructor));
    }

    @Test
    public void workspaceIndexIncludesExplicitConstructor() {
        var compiler = new JavaCompilerService(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        var parseTasks = List.of(
                compiler.parse(workspaceRoot.resolve("pkg/MyClass.java")));
        var index = WorkspaceTypeIndex.fromParseTrees(parseTasks);
        var ctors = index.constructors("pkg.MyClass");
        assertThat("explicit constructor should be indexed", ctors, not(empty()));
        assertThat(ctors.get(0).parameterNames, is(new String[]{"value", "count"}));
    }

    @Test
    public void constructorsNotInheritedInMembers() {
        var compiler = new JavaCompilerService(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        var parseTasks = List.of(
                compiler.parse(workspaceRoot.resolve("pkg/MyClass.java")),
                compiler.parse(workspaceRoot.resolve("pkg/Usage.java")));
        var index = WorkspaceTypeIndex.fromParseTrees(parseTasks);
        var members = index.members("pkg.Usage", false);
        var ctorMembers = members.stream()
                .filter(m -> m.kind == CompletionItemKind.Constructor)
                .toList();
        assertThat("parent constructors should not be inherited", ctorMembers.size(), is(0));
    }

    @Test
    public void inlayHintsWorkForRecordConstructor() {
        var compiler = new JavaCompilerService(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        var usageFile = workspaceRoot.resolve("pkg/Usage.java");

        var parseTasks = List.of(
                compiler.parse(workspaceRoot.resolve("pkg/MyRecord.java")),
                compiler.parse(workspaceRoot.resolve("pkg/MyClass.java")));
        var wsIndex = WorkspaceTypeIndex.fromParseTrees(parseTasks);
        var typeIndex = new TypeIndexRouter(wsIndex, new ExternalBinaryTypeIndex(compiler));

        var provider = new InlayHintProvider(compiler, typeIndex);
        var wholeFile = new Range(new Position(0, 0), new Position(100, 0));
        var hints = provider.inlayHints(usageFile, wholeFile);

        var labels = hints.stream().map(h -> h.label).toList();
        assertThat("should have name: hint for record constructor", labels, hasItem("name:"));
        assertThat("should have age: hint for record constructor", labels, hasItem("age:"));
    }

    @Test
    public void inlayHintsWorkForExplicitConstructor() {
        var compiler = new JavaCompilerService(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        var usageFile = workspaceRoot.resolve("pkg/Usage.java");

        var parseTasks = List.of(
                compiler.parse(workspaceRoot.resolve("pkg/MyRecord.java")),
                compiler.parse(workspaceRoot.resolve("pkg/MyClass.java")));
        var wsIndex = WorkspaceTypeIndex.fromParseTrees(parseTasks);
        var typeIndex = new TypeIndexRouter(wsIndex, new ExternalBinaryTypeIndex(compiler));

        var provider = new InlayHintProvider(compiler, typeIndex);
        var wholeFile = new Range(new Position(0, 0), new Position(100, 0));
        var hints = provider.inlayHints(usageFile, wholeFile);

        var labels = hints.stream().map(h -> h.label).toList();
        assertThat("should have value: hint for explicit constructor", labels, hasItem("value:"));
        assertThat("should have count: hint for explicit constructor", labels, hasItem("count:"));
    }

    @Test
    public void multipleCompileFastCallsDontFailWithDuplicateContext() {
        var compiler = new JavaCompilerService(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        var file = workspaceRoot.resolve("pkg/MyClass.java");

        // First compile — populates cache and releases slot
        try (var task = compiler.compile(file)) {
            assertThat(task.root(), notNullValue());
        }

        // Modify file to force cache miss
        try {
            Files.writeString(file,
                    "package pkg;\nclass MyClass {\n" +
                    "    private String value;\n" +
                    "    public MyClass(String value, int count, boolean flag) { this.value = value; }\n" +
                    "}\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        FileStore.externalChange(file);

        // Second compile — reuses the slot context; should NOT throw
        try (var task = compiler.compile(file)) {
            assertThat(task.root(), notNullValue());
        }

        // Third compile after another change — triple-confirms no leak
        try {
            Files.writeString(file,
                    "package pkg;\nclass MyClass {\n" +
                    "    private String value;\n" +
                    "    public MyClass(String value) { this.value = value; }\n" +
                    "}\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        FileStore.externalChange(file);

        try (var task = compiler.compile(file)) {
            assertThat(task.root(), notNullValue());
        }
    }
}
