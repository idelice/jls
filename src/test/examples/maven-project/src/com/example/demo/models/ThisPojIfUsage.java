package com.example.demo.models;

import org.springframework.util.StringUtils;

public class ThisPojIfUsage {
    void test() {
        var ksk = new ThisPoj();
        if (StringUtils.isEmpty(ksk.getFoo())) {
        }
    }
}
