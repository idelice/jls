package org.javacs.example;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
class GetterFoo {
    private GetterBar bar;
}

@Slf4j
@Data
class GetterBar {
    private String a;
}

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
class GetterBiz {
    @Setter private GetterFoo foo;
}

public class LombokNestedGetterCompletion {
    public void something(GetterBiz biz) {
        var foo = biz.getFoo();
        var bar = foo.getBar();
        bar.
    }
}
