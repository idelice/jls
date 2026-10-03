package org.javacs.example;

interface SetupChannel {
    void setup(int numberOfChannels);
    boolean isBroadcast();
}

class SetupPartitioner implements SetupChannel {
    @Override public void setup(int numberOfChannels) {}
    public void setup(String configuration) {}
    @Override public boolean isBroadcast() { return false; }
    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return 1; }
}

class SignatureHelpOverrides {
    void test(SetupPartitioner copy) throws InterruptedException {
        copy.setup(1);
        copy.isBroadcast();
        copy.equals(this);
        copy.hashCode();
        copy.wait(1);
    }
}

class DerivedSetupPartitioner extends SetupPartitioner {
    @Override public void setup(int channels) {}
    public void setup(long channels) {}
}

class InheritedSetupUsage {
    void test(DerivedSetupPartitioner copy) {
        copy.setup(1);
    }
}
