package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.containsString;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.javacs.completion.CompositeTypeIndex;
import org.javacs.completion.CompletionProvider;
import org.javacs.completion.ExternalBinaryTypeIndex;
import org.javacs.completion.WorkspaceTypeIndex;
import org.javacs.navigation.DefinitionProvider;
import org.junit.Assert;
import org.junit.Test;

public class ExternalBinaryTypeIndexTest {
    @Test
    public void completesMembersFromExternalBinaryWithoutSourceJar() throws Exception {
        var fixture =
                createFixture(
                        false,
                        "package ext;\n"
                                + "public class ExternalPojo {\n"
                                + "  private String name;\n"
                                + "  public String getName() { return name; }\n"
                                + "  public void setName(String name) { this.name = name; }\n"
                                + "}\n",
                        "package app;\n"
                                + "import ext.ExternalPojo;\n"
                                + "class UseExternal {\n"
                                + "  void test() {\n"
                                + "    ExternalPojo user = new ExternalPojo();\n"
                                + "    user.getName();\n"
                                + "    user.setName(\"x\");\n"
                                + "  }\n"
                                + "}\n");
        try {
            assertThat(completionLabels(fixture, false, "user.ge"), hasItems("getName"));
            assertThat(completionLabels(fixture, false, "user.se"), hasItems("setName"));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void gotoDefinitionUsesAttachedSourceJarForExternalType() throws Exception {
        var fixture =
                createFixture(
                        false,
                        "package ext;\n"
                                + "public class ExternalPojo {\n"
                                + "  private String name;\n"
                                + "  public String getName() { return name; }\n"
                                + "}\n",
                        "package app;\n"
                                + "import ext.ExternalPojo;\n"
                                + "class UseExternal {\n"
                                + "  void test() {\n"
                                + "    ExternalPojo user = new ExternalPojo();\n"
                                + "  }\n"
                                + "}\n");
        try {
            var cursor = position(fixture.consumerFile, "new ExternalPojo()", "new ".length());
            var locations =
                    new DefinitionProvider(fixture.compiler(true), fixture.index(), fixture.consumerFile, cursor.line, cursor.character)
                            .find();
            assertThat(locations, not(empty()));
            var uri = locations.get(0).uri.toString();
            assertThat(uri, containsString("jls-jar-sources"));
            assertThat(uri, containsString("ExternalPojo.java"));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void gotoDefinitionUsesAttachedSourceJarForExternalMethod() throws Exception {
        var fixture =
                createFixture(
                        false,
                        "package ext;\n"
                                + "public class ExternalPojo {\n"
                                + "  private String name;\n"
                                + "  public String getName() { return name; }\n"
                                + "}\n",
                        "package app;\n"
                                + "import ext.ExternalPojo;\n"
                                + "class UseExternal {\n"
                                + "  void test() {\n"
                                + "    ExternalPojo user = new ExternalPojo();\n"
                                + "    user.getName();\n"
                                + "  }\n"
                                + "}\n");
        try {
            var locations = definitionLocations(fixture, "getName()");
            assertThat(locations, not(empty()));
            var uri = locations.get(0).uri.toString();
            assertThat(uri, containsString("jls-jar-sources"));
            assertThat(uri, containsString("ExternalPojo.java"));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void completesLombokGeneratedMembersFromExternalBinary() throws Exception {
        var fixture =
                createFixture(
                        true,
                        "package ext;\n"
                                + "import lombok.Data;\n"
                                + "@Data\n"
                                + "public class ExternalLombokPojo {\n"
                                + "  private String name;\n"
                                + "}\n",
                        "package app;\n"
                                + "import ext.ExternalLombokPojo;\n"
                                + "class UseExternalLombok {\n"
                                + "  void test() {\n"
                                + "    ExternalLombokPojo user = new ExternalLombokPojo();\n"
                                + "    user.getName();\n"
                                + "    user.setName(\"x\");\n"
                                + "  }\n"
                                + "}\n");
        try {
            assertThat(completionLabels(fixture, true, "user.ge"), hasItems("getName"));
            assertThat(completionLabels(fixture, true, "user.se"), hasItems("setName"));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void gotoDefinitionUsesGeneratedStubForExternalLombokMethod() throws Exception {
        var fixture =
                createFixture(
                        true,
                        "package ext;\n"
                                + "import lombok.Data;\n"
                                + "@Data\n"
                                + "public class ExternalLombokPojo {\n"
                                + "  private String name;\n"
                                + "}\n",
                        "package app;\n"
                                + "import ext.ExternalLombokPojo;\n"
                                + "class UseExternalLombok {\n"
                                + "  void test() {\n"
                                + "    ExternalLombokPojo user = new ExternalLombokPojo();\n"
                                + "    user.getName();\n"
                                + "  }\n"
                                + "}\n");
        try {
            var locations = definitionLocations(fixture, "getName()");
            assertThat(locations, not(empty()));
            var uri = locations.get(0).uri.toString();
            assertThat(uri, containsString("jls-binary-stubs"));
            assertThat(uri, containsString("ExternalLombokPojo.java"));
        } finally {
            fixture.close();
        }
    }

    @Test
    public void gotoDefinitionUsesGeneratedStubForExternalConstructorWithoutSources() throws Exception {
        var fixture =
                createFixture(
                        false,
                        "package ext;\n"
                                + "public class ExternalPojo {\n"
                                + "  public ExternalPojo(String name) {}\n"
                                + "}\n",
                        "package app;\n"
                                + "import ext.ExternalPojo;\n"
                                + "class UseExternal {\n"
                                + "  void test() {\n"
                                + "    new ExternalPojo(\"x\");\n"
                                + "  }\n"
                                + "}\n");
        try {
            var cursor = position(fixture.consumerFile, "new ExternalPojo(", "new ".length());
            var locations =
                    new DefinitionProvider(fixture.compiler(false), fixture.index(), fixture.consumerFile, cursor.line, cursor.character)
                            .find();
            assertThat(locations, not(empty()));
            var uri = locations.get(0).uri.toString();
            assertThat(uri, containsString("jls-binary-stubs"));
            assertThat(uri, containsString("ExternalPojo.java"));
        } finally {
            fixture.close();
        }
    }

    private static List<String> completionLabels(Fixture fixture, boolean includeSourceJar, String needle)
            throws Exception {
        var index = fixture.index();
        var provider = new CompletionProvider(fixture.compiler(includeSourceJar), index, 1L);
        var cursor = positionAfter(fixture.consumerFile, needle);
        var items = provider.complete(fixture.consumerFile, cursor.line, cursor.character).items;
        var labels = new ArrayList<String>();
        for (var item : items) {
            labels.add(item.label);
        }
        return labels;
    }

    private static List<org.javacs.lsp.Location> definitionLocations(Fixture fixture, String needle)
            throws Exception {
        var index = fixture.index();
        var cursor = positionAt(fixture.consumerFile, needle);
        return new DefinitionProvider(fixture.compiler(true), index, fixture.consumerFile, cursor.line, cursor.character)
                .find();
    }

    private static Fixture createFixture(boolean lombok, String externalSource, String consumerSource)
            throws Exception {
        var root = Files.createTempDirectory("external-binary-index-");
        var externalSrcRoot = root.resolve("external-src");
        var consumerSrcRoot = root.resolve("workspace");
        var externalSourceFile =
                writeJavaSource(
                        externalSrcRoot,
                        lombok ? "ext/ExternalLombokPojo.java" : "ext/ExternalPojo.java",
                        externalSource);
        var consumerFile = writeJavaSource(consumerSrcRoot, "app/UseExternal.java", consumerSource);

        var classesDir = root.resolve("external-classes");
        Files.createDirectories(classesDir);
        compileExternalSource(externalSourceFile, classesDir, lombok);

        var binaryJar = root.resolve("external-lib.jar");
        var sourceJar = root.resolve("external-lib-sources.jar");
        createJar(classesDir, binaryJar);
        createJar(externalSrcRoot, sourceJar);

        FileStore.setWorkspaceRoots(Set.of(consumerSrcRoot));
        var compileCompiler = new JavaCompilerService(Set.of(binaryJar), Collections.emptySet(), Set.of(), Set.of());
        var definitionCompiler = new JavaCompilerService(Set.of(binaryJar), Set.of(sourceJar), Set.of(), Set.of());
        return new Fixture(root, consumerSrcRoot, consumerFile, compileCompiler, definitionCompiler, binaryJar, sourceJar);
    }

    private static void compileExternalSource(Path sourceFile, Path classesDir, boolean lombok) throws IOException {
        var javac = ToolProvider.getSystemJavaCompiler();
        Assert.assertNotNull("System compiler is required for external binary tests", javac);
        try (var fileManager = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = fileManager.getJavaFileObjectsFromPaths(List.of(sourceFile));
            var options = new ArrayList<String>();
            options.add("-parameters");
            options.add("-d");
            options.add(classesDir.toString());
            if (lombok) {
                var lombokJar = runtimeJarPath("lombok.Data");
                options.add("-classpath");
                options.add(lombokJar.toString());
            }
            var output = new StringWriter();
            var ok = javac.getTask(output, fileManager, null, options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                Assert.fail("failed to compile external source: " + output);
            }
        }
    }

    private static Path runtimeJarPath(String className) {
        try {
            var type = Class.forName(className);
            var codeSource = type.getProtectionDomain().getCodeSource();
            Assert.assertNotNull("Missing code source for " + className, codeSource);
            return Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new RuntimeException("Unable to locate runtime jar for " + className, e);
        }
    }

    private static Path writeJavaSource(Path root, String relativePath, String source) throws IOException {
        var file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private static void createJar(Path root, Path jar) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(
                            file -> {
                                var entry = root.relativize(file).toString().replace('\\', '/');
                                try {
                                    out.putNextEntry(new JarEntry(entry));
                                    Files.copy(file, out);
                                    out.closeEntry();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static Cursor positionAfter(Path file, String needle) throws IOException {
        return position(file, needle, needle.length());
    }

    private static Cursor positionAt(Path file, String needle) throws IOException {
        return position(file, needle, 0);
    }

    private static Cursor position(Path file, String needle, int extra) throws IOException {
        var text = Files.readString(file);
        var offset = text.indexOf(needle);
        Assert.assertTrue("missing needle: " + needle, offset >= 0);
        if (needle.startsWith("import ")) {
            var importedType = needle.substring("import ".length(), needle.length() - 1);
            var simpleName = importedType.substring(importedType.lastIndexOf('.') + 1);
            offset += importedType.lastIndexOf('.') + 1;
            needle = simpleName;
        }
        offset += extra;
        int line = 0;
        int character = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new Cursor(line + 1, character + 1);
    }

    private record Cursor(int line, int character) {}

    private record Fixture(
            Path root,
            Path workspaceRoot,
            Path consumerFile,
            JavaCompilerService compileCompiler,
            JavaCompilerService definitionCompiler,
            Path binaryJar,
            Path sourceJar) implements AutoCloseable {
        CompositeTypeIndex index() {
            try (var task = compileCompiler.compileFast(consumerFile)) {
                return new CompositeTypeIndex(
                        WorkspaceTypeIndex.workspaceDeclarations(task),
                        new ExternalBinaryTypeIndex(definitionCompiler));
            }
        }

        JavaCompilerService compiler(boolean includeSourceJar) {
            return includeSourceJar ? definitionCompiler : compileCompiler;
        }

        @Override
        public void close() throws Exception {
            FileStore.reset();
            deleteTree(root);
        }
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
}
