package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.javacs.lsp.Location;
import org.junit.Test;

/**
 * Test that find references on interface methods finds the implementing/overriding methods.
 */
public class OverrideReferencesTest extends CompletionsBase {

  @Test
  public void findOverridingMethod() {
    var interfaceFile = FindResource.uri("org/javacs/example/IProc.java");

    System.out.println("=== Testing find references on interface method ===");
    System.out.println("Interface file: " + interfaceFile);

    // Find references to IProc.process (the interface method, line 3, column 20)
    var context = new org.javacs.lsp.ReferenceContext();
    context.includeDeclaration = false;
    var params = new org.javacs.lsp.ReferenceParams();
    params.textDocument = new org.javacs.lsp.TextDocumentIdentifier(interfaceFile);
    params.position = new org.javacs.lsp.Position(2, 20);  // Line 3, column 20 (0-indexed)
    params.context = context;

    var referencesOpt = server.findReferences(params);
    assertThat("findReferences should return a result", referencesOpt.isPresent());

    var references = referencesOpt.get();
    System.out.println("=== References to IProc.process ===");
    for (var ref : references) {
      System.out.println("  " + ref.uri + " @ line " + (ref.range.start.line + 1));
    }
    System.out.println("=== End References ===");

    // Should find at least the override in ProcessorImpl
    var implReferences = references.stream()
        .filter(ref -> ref.uri.toString().contains("ProcessorImpl"))
        .toList();

    assertThat(
        "Should find the @Override method in ProcessorImpl when finding references to interface method",
        implReferences,
        hasSize(greaterThan(0)));
  }
}
