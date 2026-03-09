package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.stream.Collectors;
import org.junit.Test;

public class LombokProcessorIntegrationTest {
    @Test
    public void diagnosticsResolveGeneratedMembersAcrossUnits() {
        var compiler = LanguageServerFixture.getCompilerProvider();
        var useFile = FindResource.path("org/javacs/example/LombokCrossTypeDiagnostics.java");

        try (var task = compiler.compile(useFile)) {
            assertThat(Paths.get(task.root().getSourceFile().toUri()), is(useFile));
            var sourcesInBatch =
                    task.roots.stream()
                            .map(root -> Paths.get(root.getSourceFile().toUri()).getFileName().toString())
                            .collect(Collectors.toSet());
            assertThat(sourcesInBatch, hasItem("LombokCrossTypeModel.java"));

            var unresolvedOnUseFile =
                    task.diagnostics.stream()
                            .filter(d -> d.getSource() != null)
                            .filter(d -> useFile.toUri().equals(d.getSource().toUri()))
                            .filter(d -> d.getCode() != null && d.getCode().startsWith("compiler.err.cant.resolve"))
                            .map(d -> d.getMessage(null))
                            .collect(Collectors.toList());
            assertTrue("unexpected unresolved symbols: " + unresolvedOnUseFile, unresolvedOnUseFile.isEmpty());
        }
    }

    @Test
    public void diagnosticsResolveGeneratedMembersAcrossNestedLombokUnits() {
        var compiler = LanguageServerFixture.getCompilerProvider();
        var useFile = FindResource.path("org/javacs/example/LombokNestedDiagnostics.java");

        try (var task = compiler.compile(useFile)) {
            assertThat(Paths.get(task.root().getSourceFile().toUri()), is(useFile));
            var sourcesInBatch =
                    task.roots.stream()
                            .map(root -> Paths.get(root.getSourceFile().toUri()).getFileName().toString())
                            .collect(Collectors.toSet());
            assertThat(sourcesInBatch, hasItem("LombokNestedOuter.java"));
            assertThat(sourcesInBatch, hasItem("LombokNestedBar.java"));

            var unresolvedOnUseFile =
                    task.diagnostics.stream()
                            .filter(d -> d.getSource() != null)
                            .filter(d -> useFile.toUri().equals(d.getSource().toUri()))
                            .filter(d -> d.getCode() != null && d.getCode().startsWith("compiler.err.cant.resolve"))
                            .map(d -> d.getMessage(null))
                            .collect(Collectors.toList());
            assertTrue("unexpected unresolved symbols: " + unresolvedOnUseFile, unresolvedOnUseFile.isEmpty());
        }
    }
}
