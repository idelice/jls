package org.javacs.example;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class LombokDataMapHoverCompletion implements Serializable {
    private Map<String, String> dataMap = new HashMap<>();

    void test() {
        this.getDataMap();
        this.getDataMap().
    }
}
