package org.javacs.example;

import lombok.Value;

/**
 * Example with @Value annotation (immutable variant of @Data).
 * @Value generates getters, toString, equals, hashCode, and allArgsConstructor.
 * Note: @Value does NOT generate setters (immutable).
 */
@Value
public class LombokValueExample {
    String immutableName;
    int immutableAge;
}
