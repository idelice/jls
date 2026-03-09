package org.javacs.repro.service;

import org.javacs.repro.model.ReproTxn;

public class ReproService {
    void run() {
        var txn = new ReproTxn();
        txn.getMsref();
        txn.setMsref("value");
    }
}
