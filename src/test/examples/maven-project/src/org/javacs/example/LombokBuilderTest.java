package org.javacs.example;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
class LombokBuilderTest {
    private String family;
    private int count;
    private boolean enabled;

    void use() {
        LombokBuilderTest.builder()
                .family("x")
                .count(1)
                .enabled(true);
    }
}
