package org.javacs.example;

class PackagePrivateTypeUse {
    PackagePrivateType field;

    PackagePrivateType make() {
        return new PackagePrivateType();
    }

    org.javacs.other.PackagePrivateType other;
}
