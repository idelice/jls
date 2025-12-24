package org.javacs.other;

import java.util.function.Consumer;

class ImplementsConsumer implements Consumer<String> {
    @Override
    public void accept(String foo) { }
}