package com.example.demo.service;

import com.example.demo.models.ThisPoj;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
public class ServiceTwo {
    public void hoo() throws Exception {
        StringUtils.cleanPath("asd");
        log.info("asd");

        var ksk = new ThisPoj();
        if (StringUtils.isEmpty(ksk.getFoo())) {
        }
    }
}
