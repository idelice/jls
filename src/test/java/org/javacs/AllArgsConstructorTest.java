package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.Diagnostic;
import org.junit.Before;
import org.junit.Test;

/**
 * Test that @AllArgsConstructor works correctly with @Data.
 * When @Data and @AllArgsConstructor are combined, Lombok should generate
 * an all-args constructor, not a no-args constructor.
 */
public class AllArgsConstructorTest {
  private static List<String> errors = new ArrayList<>();
  protected static final JavaLanguageServer server =
      LanguageServerFixture.getJavaLanguageServer(AllArgsConstructorTest::onError);

  private static void onError(Diagnostic error) {
    var string = String.format("%s(%d): %s", error.code, error.range.start.line + 1, error.message);
    errors.add(string);
  }

  @Before
  public void setup() {
    errors.clear();
  }

  @Test
  public void allArgsConstructorWithData() {
    var file = FindResource.path("org/javacs/example/AsyncEventUser.java");

    System.out.println("=== Testing @AllArgsConstructor with @Data ===");
    System.out.println("File: " + file);

    server.lint(List.of(file));

    // Print all errors for debugging
    System.out.println("=== All Diagnostics ===");
    for (var error : errors) {
      System.out.println("  " + error);
    }
    System.out.println("=== End Diagnostics ===");

    // Check for "cannot apply symbol" errors for the constructor
    var constructorErrors = errors.stream()
        .filter(err -> err.contains("cant.apply.symbol") && err.contains("AsyncEvent"))
        .toList();

    System.out.println("Constructor errors for AsyncEvent: " + constructorErrors);

    // Should have NO "cannot apply symbol" errors
    // The @AllArgsConstructor should generate a constructor that accepts all 4 field arguments
    assertThat(
        "Should have NO 'cant.apply.symbol' errors for AsyncEvent constructor. "
            + "@AllArgsConstructor should generate constructor with all field parameters.",
        constructorErrors,
        empty());
  }
}
