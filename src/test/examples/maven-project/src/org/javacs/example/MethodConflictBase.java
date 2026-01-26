package org.javacs.example;

import lombok.Data;

@Data
public class MethodConflictBase {
  private String one;

  // Custom static method that conflicts with Lombok-generated getter
  public static String getOne(String a) {
    return "custom: " + a;
  }
}
