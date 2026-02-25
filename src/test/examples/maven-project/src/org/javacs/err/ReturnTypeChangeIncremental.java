package org.javacs.err;

interface ReturnTypeContract {
    void compute();
}

public class ReturnTypeChangeIncremental implements ReturnTypeContract {
    @Override
    public void compute() {}
}
