package org.javacs.example;

class RefValueOfStringLiteral {
    RefValueOfColor fromName() {
        return RefValueOfColor.valueOf("RED");
    }

    RefValueOfColor fromConstant() {
        return RefValueOfColor.RED;
    }
}
