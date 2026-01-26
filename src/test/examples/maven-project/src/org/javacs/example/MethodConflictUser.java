package org.javacs.example;

public class MethodConflictUser {
  public void testConflict() {
    var instance = new MethodConflictBase();
    // This should call the Lombok-generated getter, not the static method
    // Error: "method getOne in class MethodConflictBase cannot be applied to given types;
    //         required: java.lang.String, found: no arguments"
    // Should be FILTERED by our fix
    instance.getOne();
  }
}
