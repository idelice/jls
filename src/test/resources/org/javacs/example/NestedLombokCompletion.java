package org.javacs.example;

import lombok.Data;

/** Test nested Lombok types and completion for generated methods. */
public class NestedLombokCompletion {
  @Data
  static class Container {
    private NestedLombokType nested;
  }

  @Data
  static class NestedLombokType {
    private String value;
    private int count;
  }

  void test() {
    var c = new Container();
    c.getNested().get // should complete with getValue(), getCount(), etc.
  }
}
