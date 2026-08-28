package org.javacs.warn;

class UnusedPrivateTypes {
    // This private record is never used — should warn
    private record UnusedRecord(String name) {}

    // This private class is never used — should warn
    private class UnusedInner {}

    // This private record IS used — should NOT warn
    private record UsedRecord(int value) {}

    // This private class IS used — should NOT warn
    private class UsedInner {
        int x;
    }

    void use() {
        var r = new UsedRecord(42);
        var i = new UsedInner();
        System.out.println(r.value() + i.x);
    }
}
