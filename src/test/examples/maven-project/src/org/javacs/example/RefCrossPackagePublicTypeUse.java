package org.javacs.example;

import org.javacs.other.RefCrossPackagePublicType;

public class RefCrossPackagePublicTypeUse {
    RefCrossPackagePublicType field;

    RefCrossPackagePublicType make() {
        return new RefCrossPackagePublicType();
    }
}
