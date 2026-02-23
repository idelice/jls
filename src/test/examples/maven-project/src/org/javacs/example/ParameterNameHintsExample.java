package org.javacs.example;

import java.util.Random;

public class ParameterNameHintsExample {
    static class Foo {}

    static class Holder {
        void setFoo(Foo value) {}
    }

    static String getOne(String text) {
        return text;
    }

    static void acceptsNullable(Foo maybe) {}

    void run(int length, int index, Foo foo) {
        StringBuilder sb = new StringBuilder(length);
        new Random().nextInt(index);
        getOne("asd");
        var holder = new Holder();
        holder.setFoo(foo);
        acceptsNullable(null);
        sb.toString();
    }
}
