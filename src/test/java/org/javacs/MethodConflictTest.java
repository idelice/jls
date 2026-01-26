package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.Diagnostic;
import org.junit.Before;
import org.junit.Test;

/**
 * Test that verifies the method conflict fix works correctly.
 *
 * Issue: When a class has:
 * - A Lombok-generated getter (e.g., getOne() for field 'one')
 * - A custom static method with the same name (e.g., getOne(String))
 *
 * Calling instance.getOne() should use the Lombok getter, not trigger an error
 * about the static method signature not matching.
 */
public class MethodConflictTest {
  private static List<String> errors = new ArrayList<>();
  protected static final JavaLanguageServer server =
      LanguageServerFixture.getJavaLanguageServer(MethodConflictTest::onError);

  private static void onError(Diagnostic error) {
    var string = String.format("%s(%d): %s", error.code, error.range.start.line + 1, error.message);
    errors.add(string);
  }

  @Before
  public void setup() {
    errors.clear();
  }

  @Test
  public void methodConflictFiltered() {
    var file = FindResource.path("org/javacs/example/MethodConflictUser.java");

    System.out.println("=== Running methodConflictFiltered test ===");
    System.out.println("File: " + file);

    server.lint(List.of(file));

    // Print all errors for debugging
    System.out.println("=== All Diagnostics ===");
    for (var error : errors) {
      System.out.println("  " + error);
    }
    System.out.println("=== End Diagnostics ===");

    // The key check: verify no "cant.apply.symbol" error for getOne
    var getOneErrors = errors.stream()
        .filter(err -> err.contains("cant.apply.symbol") && err.contains("getOne"))
        .toList();

    System.out.println("Cannot apply symbol errors for getOne: " + getOneErrors);

    // This should be EMPTY if our filter works correctly
    // The "cannot apply symbol" error for getOne should be filtered out because:
    // 1. getOne is a Lombok-generated getter (0 parameters)
    // 2. Our filter recognizes it as a generated method and removes the error
    assertThat(
        "Should have NO 'cant.apply.symbol' errors for getOne(). "
            + "The static method getOne(String) should not cause an error when calling instance.getOne()",
        getOneErrors,
        empty());
  }
}
