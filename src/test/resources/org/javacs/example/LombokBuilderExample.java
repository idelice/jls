package org.javacs.example;

import lombok.Builder;
import lombok.Getter;

/**
 * Example with @Builder annotation.
 * Tests builder method generation.
 * Note: @Builder generates a builder() static method and inner Builder class.
 */
@Getter
@Builder
public class LombokBuilderExample {
    private String builderId;
    private String description;
    private int count;
}
