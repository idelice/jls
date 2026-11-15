package org.javacs.example;

class TypeRefAnonymous {
    interface TypeRefAnonymousRunnable {
        void run();
    }

    Object make() {
        return new TypeRefAnonymousRunnable() {
            public void run() {}
        };
    }
}
