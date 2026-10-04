package org.javacs;

import static org.junit.Assert.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.tools.Diagnostic;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Release API boundaries and fresh source symbols through the compiler service. */
public class ReleasePlatformReuseTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void releaseContextSurvivesMoreThan64Borrows() throws Exception {
        Path file = folder.newFile("Usage.java").toPath();
        var compiler = new ReusableCompiler();
        try (var files = javax.tools.ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            long firstContext = -1;
            for (int i = 0; i < 130; i++) {
                var source = new SourceFileObject(file,
                        "class Usage { int value() { return " + i + "; } }", Instant.now());
                var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
                try (var borrow = compiler.borrow(files, diagnostics,
                        List.of("-proc:none", "--release", "8"), List.of(source), false)) {
                    borrow.task.analyze();
                    assertFalse(diagnostics.getDiagnostics().stream()
                            .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
                    if (i == 0) firstContext = borrow.id();
                    assertEquals("Context replaced on borrow " + (i + 1), firstContext, borrow.id());
                }
            }
        } finally {
            compiler.discard();
        }
    }

    @Test public void repeatedEditsKeepFreshTypesAndReleaseApiRestrictions() throws Exception {
        Path file = folder.newFile("Usage.java").toPath();
        try (var compiler = new JavaCompilerService(Set.of(), Set.of(), Set.of(), List.of("--release", "8"))) {
            for (int i = 0; i < 130; i++) {
                String type = i % 2 == 0 ? "String" : "Integer";
                String value = i % 2 == 0 ? "\"x\"" : "1";
                String source = "class Usage { " + type + " value() { return " + value + "; } }";
                try (var task = compiler.compile(List.of(new SourceFileObject(file, source, Instant.now())))) {
                    var method = task.elements.getTypeElement("Usage").getEnclosedElements().stream()
                            .filter(e -> e.getSimpleName().contentEquals("value")).findFirst().orElseThrow();
                    assertEquals("()java.lang." + type, method.asType().toString());
                    assertFalse(task.diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
                }
                if (i % 16 == 0) {
                    try (var task = compiler.compile(List.of(new SourceFileObject(file,
                            "class Usage { boolean value() { return \"x\".isBlank(); } }", Instant.now())))) {
                        assertTrue("Java 11 method exposed by --release 8", task.diagnostics.stream()
                                .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
                    }
                }
            }
        }
    }

    @Test public void differentReleaseCompilersDoNotShareApiVisibility() throws Exception {
        Path file = folder.newFile("Usage.java").toPath();
        String source = "class Usage { boolean value() { return \"x\".isBlank(); } }";
        for (String release : List.of("11", "8", "11")) {
            try (var compiler = new JavaCompilerService(Set.of(), Set.of(), Set.of(), List.of("--release", release));
                    var task = compiler.compile(List.of(new SourceFileObject(file, source, Instant.now())))) {
                assertEquals(release.equals("8"), task.diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
            }
        }
    }

    @Test public void conflictingReleaseOptionsStillFailInitialValidation() throws Exception {
        Path file = folder.newFile("Usage.java").toPath();
        for (String conflict : List.of("--source", "--target")) {
            try (var compiler = new JavaCompilerService(Set.of(), Set.of(), Set.of(),
                    List.of("--release", "8", conflict, "8"))) {
                assertThrows(IllegalArgumentException.class, () -> {
                    try (var ignored = compiler.compile(List.of(new SourceFileObject(file, "class Usage {}", Instant.now())))) {}
                });
            }
        }
    }
}
