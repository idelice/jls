package simple;

import java.nio.file.AccessMode;

class ItalicEnumConst {
    void test() {
        var x = AccessMode.READ;
    }

    void doSwitch(AccessMode x) {
        switch (x) {
            case WRITE:
        }
    }
}