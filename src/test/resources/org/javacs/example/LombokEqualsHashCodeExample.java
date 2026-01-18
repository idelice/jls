package org.javacs.example;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Example with @EqualsAndHashCode annotation.
 * Tests equals and hashCode generation.
 */
@Getter
@EqualsAndHashCode
public class LombokEqualsHashCodeExample {
    private String code;
    private String value;
}
