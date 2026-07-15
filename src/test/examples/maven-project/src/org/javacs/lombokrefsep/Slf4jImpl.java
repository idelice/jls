package org.javacs.lombokrefsep;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class Slf4jImpl implements SepInterface {
    String use(String input) {
        return hello(input);
    }
}
