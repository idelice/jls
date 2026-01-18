package org.javacs.example;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
class LombokSignatureHelp<T> {
    private List<String> items;
    private boolean active;

    void test() {
        var pojo = LombokSignatureHelp.<String>builder().build();
        pojo.setItems( );
        LombokSignatureHelp.<String>builder().items( );
    }
}
