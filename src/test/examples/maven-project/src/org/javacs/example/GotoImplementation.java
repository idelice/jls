package org.javacs.example;

class GotoImplementation {
    void main(IGoto i) {
        i.virtualMethod();
    }

    interface IGoto {
        void virtualMethod();
    }

    class CGoto implements IGoto {
        @Override
        public void virtualMethod() { }
    }
}