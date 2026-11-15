package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.provider.InlayHintProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class InlayHintsTest {
    static {
        Main.setRootFormat();
    }

    private static Path workspaceRoot;
    private static JavaCompilerService compiler;

    @BeforeClass
    public static void setup() throws Exception {
        workspaceRoot = Files.createTempDirectory("inlay-hints-test-");
        var pkgDir = workspaceRoot.resolve("pkg");
        Files.createDirectories(pkgDir);

        Files.writeString(
                pkgDir.resolve("MyRecord.java"), "package pkg;\nrecord MyRecord(String name, int age) {}\n");
        Files.writeString(
                pkgDir.resolve("MyClass.java"),
                "package pkg;\nclass MyClass {\n"
                        + "    private String value;\n"
                        + "    public MyClass(String value, int count) { this.value = value; }\n"
                        + "}\n");
        Files.writeString(
                pkgDir.resolve("Usage.java"),
                "package pkg;\nclass Usage {\n"
                        + "    void test() {\n"
                        + "        var r = new MyRecord(\"hello\", 42);\n"
                        + "        var c = new MyClass(\"world\", 7);\n"
                        + "    }\n"
                        + "}\n");

        FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
        compiler = new JavaCompilerService(
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    }

    @AfterClass
    public static void teardown() throws Exception {
        if (compiler != null) compiler.close();
        FileStore.reset();
        if (workspaceRoot != null) {
            Files.walk(workspaceRoot)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception e) {
                        }
                    });
        }
    }

    @Test
    public void recordConstructor() {
        var usageFile = workspaceRoot.resolve("pkg/Usage.java");

        var parseTasks = List.of(
                compiler.parse(workspaceRoot.resolve("pkg/MyRecord.java")),
                compiler.parse(workspaceRoot.resolve("pkg/MyClass.java")));
        var wsIndex = WorkspaceTypeIndex.fromParseTrees(parseTasks);

        var provider = new InlayHintProvider(compiler, wsIndex);
        var wholeFile = new Range(new Position(0, 0), new Position(100, 0));
        var labels = labels(provider, usageFile, wholeFile);
        assertThat("should have name: hint for record constructor", labels, hasItem("name:"));
        assertThat("should have age: hint for record constructor", labels, hasItem("age:"));
    }

    @Test
    public void explicitConstructor() {
        var usageFile = workspaceRoot.resolve("pkg/Usage.java");

        var parseTasks = List.of(
                compiler.parse(workspaceRoot.resolve("pkg/MyRecord.java")),
                compiler.parse(workspaceRoot.resolve("pkg/MyClass.java")));
        var wsIndex = WorkspaceTypeIndex.fromParseTrees(parseTasks);

        var provider = new InlayHintProvider(compiler, wsIndex);
        var wholeFile = new Range(new Position(0, 0), new Position(100, 0));
        var labels = labels(provider, usageFile, wholeFile);
        assertThat("should have value: hint for explicit constructor", labels, hasItem("value:"));
        assertThat("should have count: hint for explicit constructor", labels, hasItem("count:"));
    }

    private static List<String> labels(InlayHintProvider provider, Path file, Range range) {
        try (var task = compiler.compile(file)) {
            var payload = provider.inlayHints(task, file, range);
            var hints = JsonHelper.GSON.fromJson(payload.getAsJsonArray("hints"), InlayHint[].class);
            return Arrays.stream(hints).map(h -> h.label).toList();
        }
    }
}
