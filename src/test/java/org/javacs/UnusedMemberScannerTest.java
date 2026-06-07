package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.*;
import java.util.stream.Collectors;
import org.javacs.lsp.*;
import org.junit.Before;
import org.junit.Test;

public class UnusedMemberScannerTest {

    private static List<String> errors = new ArrayList<>();

    private static final JavaLanguageServer server =
            LanguageServerFixture.getJavaLanguageServer(UnusedMemberScannerTest::onError);

    private static void onError(Diagnostic error) {
        var string = String.format("%s(%d)", error.code, error.range.start.line + 1);
        errors.add(string);
    }

    @Before
    public void setup() {
        errors.clear();
    }

    @Test
    public void detectsUnusedPublicMethod() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedNonPrivate.java")));
        assertThat(errors, hasItem("unused_method(7)")); // unusedPublicMethod
    }

    @Test
    public void detectsUnusedPackageMethod() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedNonPrivate.java")));
        assertThat(errors, hasItem("unused_method(10)")); // unusedPackageMethod
    }

    @Test
    public void detectsUnusedProtectedMethod() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedNonPrivate.java")));
        assertThat(errors, hasItem("unused_method(13)")); // unusedProtectedMethod
    }

    @Test
    public void detectsUnusedPublicField() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedNonPrivate.java")));
        assertThat(errors, hasItem("unused_field(16)")); // unusedPublicField
    }

    @Test
    public void doesNotFlagUsedPublicMethod() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedNonPrivate.java")));
        assertThat(errors, not(hasItem("unused_method(4)"))); // usedPublicMethod
    }

    @Test
    public void doesNotFlagUsedField() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedNonPrivate.java")));
        assertThat(errors, not(hasItem("unused_field(18)"))); // usedPackageField
    }

    @Test
    public void doesNotFlagMethodUsedInSameFile() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedNonPrivateSameFile.java")));
        assertThat(errors, not(hasItem("unused_method(9)"))); // helperMethod — called by callerMethod
        assertThat(errors, not(hasItem("unused_field(12)"))); // helperField — read by callerMethod
    }

    @Test
    public void detectsUnusedMethodInSameFile() {
        server.lint(List.of(FindResource.path("org/javacs/warn/UnusedNonPrivateSameFile.java")));
        assertThat(errors, hasItem("unused_method(14)")); // trulyUnusedMethod
    }
}
