package org.javacs.example;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
class Foo {
    private Bar bar;
}

@Slf4j
@Data
class Bar {
    private String a;
}

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
class Biz {
    @Setter private Foo foo;
}

public class LombokNestedStringFieldCompletion {
    public void something(Biz biz) {
        var foo = biz.getFoo();
        var bar = foo.getBar();

        var a = bar.getA();
        a.
    }
}
