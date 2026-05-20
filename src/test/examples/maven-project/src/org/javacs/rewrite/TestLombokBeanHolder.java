package org.javacs.rewrite;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TestLombokBeanHolder {
    private final List<TestLombokBean> items = new ArrayList<>();
}
