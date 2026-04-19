package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.source.tree.ClassTree;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.Test;

public class LombokAnnotationsTest {
    @Test
    public void accessorInfoResolvesDataGetterAndSetterNames() {
        var parse =
                Parser.parseJavaFileObject(
                        new SourceFileObject(
                                Path.of("/tmp/LombokDataPerson.java"),
                                "package p;\n"
                                        + "import lombok.Data;\n"
                                        + "@Data class Person { private boolean active; }\n",
                                Instant.EPOCH));
        var declaration = (ClassTree) parse.root.getTypeDecls().get(0);
        var field = (com.sun.source.tree.VariableTree) declaration.getMembers().get(0);

        var accessors =
                LombokAnnotations.accessorInfo(
                        declaration.getModifiers(), field.getModifiers(), "active", "boolean");

        assertTrue(accessors.isPresent());
        assertThat(accessors.get().getterName(), is("isActive"));
        assertThat(accessors.get().setterName(), is("setActive"));
    }

    @Test
    public void accessorInfoIgnoresNonLombokFields() {
        var parse =
                Parser.parseJavaFileObject(
                        new SourceFileObject(
                                Path.of("/tmp/PlainPerson.java"),
                                "package p;\nclass Person { private String name; }\n",
                                Instant.EPOCH));
        var declaration = (ClassTree) parse.root.getTypeDecls().get(0);
        var field = (com.sun.source.tree.VariableTree) declaration.getMembers().get(0);

        assertFalse(
                LombokAnnotations.accessorInfo(
                                declaration.getModifiers(), field.getModifiers(), "name", "java.lang.String")
                        .isPresent());
    }

    @Test
    public void sourceScanOnlyMatchesKnownLombokAnnotations() throws Exception {
        var lombokFile = Files.createTempFile("lombok-scan-", ".java");
        var springFile = Files.createTempFile("spring-scan-", ".java");
        try {
            Files.writeString(lombokFile, "import lombok.Data;\n@Data class User {}\n");
            Files.writeString(springFile, "import org.springframework.stereotype.Service;\n@Service class User {}\n");

            assertTrue(LombokAnnotations.sourceMayRequireLombokExpansion(lombokFile, 200));
            assertFalse(LombokAnnotations.sourceMayRequireLombokExpansion(springFile, 200));
        } finally {
            Files.deleteIfExists(lombokFile);
            Files.deleteIfExists(springFile);
        }
    }
}
