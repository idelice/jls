package simple;

class UnderlineMutable {
    void method(int param) {
        param = 2;
        
        var local = 1;
        local = 2;

        var minus = 1;
        minus--;

        var plus = 1;
        plus++;

        var plusAssign = 1;
        plusAssign += 1;
    }
}