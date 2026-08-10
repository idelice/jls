package org.javacs.rewrite;

class TestRenameLombokFieldConsumer {
    void use(TestRenameLombokField model) {
        var value = model.getLombokRenameValue();
        model.setLombokRenameValue(value);
    }
}
