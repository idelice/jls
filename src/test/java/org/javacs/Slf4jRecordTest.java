package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.Diagnostic;
import org.junit.Before;
import org.junit.Test;

/**
 * Test that @Slf4j annotation works with records.
 * Records have special constructor syntax where Lombok needs to generate the log field.
 */
public class Slf4jRecordTest {
  private static List<String> errors = new ArrayList<>();
  protected static final JavaLanguageServer server =
      LanguageServerFixture.getJavaLanguageServer(Slf4jRecordTest::onError);

  private static void onError(Diagnostic error) {
    var string = String.format("%s(%d): %s", error.code, error.range.start.line + 1, error.message);
    errors.add(string);
  }

  @Before
  public void setup() {
    errors.clear();
  }

  @Test
  public void slf4jRecordHasLogField() {
    var file = FindResource.path("org/javacs/example/Slf4jRecord.java");

    System.out.println("=== Testing @Slf4j on record ===");
    System.out.println("File: " + file);

    server.lint(List.of(file));

    // Print all errors for debugging
    System.out.println("=== All Diagnostics ===");
    for (var error : errors) {
      System.out.println("  " + error);
    }
    System.out.println("=== End Diagnostics ===");

    // Check for "cannot find symbol" errors related to 'log'
    var logErrors = errors.stream()
        .filter(err -> err.contains("cant.resolve") && err.contains("log"))
        .toList();

    System.out.println("Cannot resolve symbol errors for log: " + logErrors);

    // Should have NO "cannot resolve symbol" errors for the log field
    // The @Slf4j annotation should make the log field available
    assertThat(
        "Should have NO 'cant.resolve' errors for log field. "
            + "@Slf4j should generate a static log field even in records.",
        logErrors,
        empty());
  }
}
