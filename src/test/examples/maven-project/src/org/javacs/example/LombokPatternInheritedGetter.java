package org.javacs.example;

import lombok.Data;
import lombok.ToString;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ToString(callSuper = true)
class PatternFoo extends PatternBaseFoo {
    private String asd;
}

@Data
@Validated
class PatternBaseFoo {
    private String dudu;
}

public class LombokPatternInheritedGetter {
    void test(Holder holder) {
        if (holder.getSomePojo() instanceof PatternFoo f) {
            var k = f.getDudu();
        }
    }

    interface Holder {
        Object getSomePojo();
    }
}
