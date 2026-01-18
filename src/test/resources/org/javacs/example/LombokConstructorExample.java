package org.javacs.example;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * Example with @NoArgsConstructor and @RequiredArgsConstructor.
 * Tests constructor generation variants.
 */
@Getter
@NoArgsConstructor
@RequiredArgsConstructor
public class LombokConstructorExample {
    private final String requiredField;
    private String optionalField;
}
