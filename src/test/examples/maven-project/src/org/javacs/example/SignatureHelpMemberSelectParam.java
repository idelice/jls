package org.javacs.example;

import java.util.Map;

public class SignatureHelpMemberSelectParam {
    void consume(Map.Entry<String, String> entry) {}

    void test(Map.Entry<String, String> entry) {
        consume(entry);
    }
}
