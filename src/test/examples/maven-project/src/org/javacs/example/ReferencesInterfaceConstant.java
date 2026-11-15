package org.javacs.example;

interface ReferencesConstantInterface {
    int X = 1;
}

class ReferencesConstantImpl implements ReferencesConstantInterface {
    int readInherited() {
        return X;
    }
}

class ReferencesConstantUse {
    int readQualified() {
        return ReferencesConstantInterface.X;
    }
}
