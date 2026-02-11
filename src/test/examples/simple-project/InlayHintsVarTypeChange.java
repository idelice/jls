class InlayHintsVarTypeChange {
    void test() {
        var value = "abc";
        use(value);
    }

    void use(Object o) {}
}
