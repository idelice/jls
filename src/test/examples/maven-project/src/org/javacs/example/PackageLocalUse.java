package org.javacs.example;

class PackageLocalUse {
    void use(PackageLocalTarget target) {
        target.count = 1;
        target.bump();
    }

    PackageLocalTarget field;
}
