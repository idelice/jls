package org.javacs.example;

/**
 * Record with field parameter 'name' that generates an accessor method.
 * Tests that find references and go-to-definition work for record fields.
 */
public record RecordFieldReferences(String name, int value) {
  public RecordFieldReferences {
    // compact constructor using name field
  }

  public void testAccessor() {
    // These should all reference the record parameter 'name'
    var n1 = name();  // line 13: name() accessor
    var n2 = name();
    var n3 = name();
  }

  public String getName() {
    return name();
  }
}
