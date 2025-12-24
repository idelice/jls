class FindFields {
    int field = 1;

    int field() {
        return 1;
    }

    void method(FindFields param) {
        int fieldReference = field;
        int thisReference = this.field;
        int methodReference = field();
        int memberReference = param.field;
        var checkForward = forwardReference;
    }

    void call(int param) { }

    int forwardReference = 2;
}