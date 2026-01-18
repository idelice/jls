package org.javacs.example;

import lombok.Getter;
import lombok.Setter;

/**
 * Example with @Getter and @Setter annotations.
 * Tests individual getter/setter generation without @Data.
 */
@Getter
@Setter
public class LombokGetterSetterExample {
    private String username;
    private int count;
}
