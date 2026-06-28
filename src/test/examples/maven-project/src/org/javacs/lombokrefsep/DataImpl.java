package org.javacs.lombokrefsep;

import lombok.Data;

@Data
class DataImpl implements SepInterface {
    private String value;

    String use(String input) {
        return hello(input);
    }
}
