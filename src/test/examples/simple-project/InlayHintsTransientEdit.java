public class InlayHintsTransientEdit {
    void take(String label, int count) {}

    void test() {
        take("one", 1);
        var first = "a";
        
        var second = "b";
        take("two", 2);
        var third = "c";
    }
}
