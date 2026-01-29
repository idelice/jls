package org.javacs.example;

/**
 * Uses RecordFieldReferences to test record accessor references.
 */
public class RecordAccessorUsage {
  public void useRecord() {
    var rec = new RecordFieldReferences("test", 42);

    // These should all reference the 'name' accessor method
    var n1 = rec.name();
    var n2 = rec.name();

    var v1 = rec.value();
  }
}
