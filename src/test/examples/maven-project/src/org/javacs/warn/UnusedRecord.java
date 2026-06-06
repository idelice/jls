package org.javacs.warn;

class UnusedRecord {
    private record Config(String name, int value, Thread.State state) {}

    private static final Config DEFAULT = new Config("default", 42, Thread.State.NEW);

    String getConfigName() {
        return DEFAULT.name();
    }

    int getConfigValue() {
        return DEFAULT.value();
    }

    Thread.State getState() {
        return DEFAULT.state();
    }
}
