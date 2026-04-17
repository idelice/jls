package org.javacs;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import org.javacs.index.ExternalBinaryTypeIndex;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.resolve.ParseTypeResolver;
import org.junit.Test;

public class ParseTypeResolverTest {
    @Test
    public void blockVariableIsNotVisibleInsideOwnInitializer() throws Exception {
        var source =
                """
                class SelfRefBlock {
                    void test() {
                        int value = value + 1;
                    }
                }
                """;

        assertVisibleDeclaration("value", source, "value + 1", 0, false);
        assertVisibleDeclaration("value", source, "value + 1;", "value + 1;".length(), true);
    }

    @Test
    public void forInitializerVariableIsNotVisibleInsideOwnInitializer() throws Exception {
        var source =
                """
                class SelfRefFor {
                    void test() {
                        for (int i = i + 1; i < 10; i++) {
                        }
                    }
                }
                """;

        assertVisibleDeclaration("i", source, "i + 1", 0, false);
        assertVisibleDeclaration("i", source, "i < 10", 0, true);
    }

    @Test
    public void identifierTypeResolutionPrefersIndexedTypeButKeepsSameFileFallback() throws Exception {
        var root = Files.createTempDirectory("parse-type-resolver-identifier-type");
        try {
            FileStore.setWorkspaceRoots(Set.of(root));

            var pkg = root.resolve("pkg");
            Files.createDirectories(pkg);

            var externalType = pkg.resolve("ExternalType.java");
            Files.writeString(
                    externalType,
                    """
                    package pkg;
                    class ExternalType {}
                    """);

            var file = pkg.resolve("Test.java");
            var source =
                    """
                    package pkg;
                    class Test {
                        static class Nested {}
                        ExternalType external;
                        Nested nested;
                    }
                    """;
            Files.writeString(file, source);

            var compiler =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());
            var parse = compiler.parse(file);
            var index = navigationIndex(compiler, externalType, file);

            var externalOffset = offset(source, "ExternalType external", 0);
            var externalResolver = new ParseTypeResolver(parse, compiler, index, externalOffset);
            assertEquals(
                    Optional.of("pkg.ExternalType"),
                    externalResolver.resolve(null, "ExternalType").map(ParseTypeResolver.TypeResolution::qualifiedType));

            var nestedOffset = offset(source, "Nested nested", 0);
            var nestedResolver = new ParseTypeResolver(parse, compiler, index, nestedOffset);
            assertEquals(
                    Optional.of("pkg.Test.Nested"),
                    nestedResolver.resolve(null, "Nested").map(ParseTypeResolver.TypeResolution::qualifiedType));
        } finally {
            FileStore.setWorkspaceRoots(Collections.emptySet());
            deleteTree(root);
        }
    }

    private static void assertVisibleDeclaration(
            String targetName, String source, String marker, int extraOffset, boolean expectedPresent)
            throws Exception {
        var root = Files.createTempDirectory("parse-type-resolver-test");
        try {
            FileStore.setWorkspaceRoots(Set.of(root));
            var file = root.resolve("Test.java");
            Files.writeString(file, source);

            var compiler =
                    new JavaCompilerService(
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet(),
                            Collections.emptySet());
            var parse = compiler.parse(file);
            var cursor = offset(source, marker, extraOffset);
            var resolver = new ParseTypeResolver(parse, compiler, TypeIndexRouter.EMPTY, cursor);

            Optional<?> declaration = resolver.resolveVisibleDeclaration(targetName);
            assertThat(
                    "visible declaration mismatch at marker `" + marker + "`",
                    declaration.isPresent(),
                    is(expectedPresent));
        } finally {
            FileStore.setWorkspaceRoots(Collections.emptySet());
            deleteTree(root);
        }
    }

    private static long offset(String source, String marker, int extraOffset) {
        var index = source.indexOf(marker);
        if (index < 0) {
            throw new AssertionError("missing marker: " + marker);
        }
        return index + extraOffset;
    }

    private static TypeIndexRouter navigationIndex(JavaCompilerService compiler, Path... files) throws Exception {
        try (var task = compiler.compile(files)) {
            return new TypeIndexRouter(
                    WorkspaceTypeIndex.from(task),
                    ExternalBinaryTypeIndex.EMPTY);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        Files.walk(root)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(
                        path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
    }
}
