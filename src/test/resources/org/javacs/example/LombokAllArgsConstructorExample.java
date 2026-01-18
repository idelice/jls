package org.javacs.example;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Example with @AllArgsConstructor and @Getter.
 * Tests constructor parameter generation.
 */
@Getter
@AllArgsConstructor
public class LombokAllArgsConstructorExample {
    private final String id;
    private final String title;
    private int priority;
}
