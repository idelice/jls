package org.javacs.repro.service;

import org.javacs.repro.model.ReproTxn;

public class ReproService {
    void run() {
        ReproTxn txn = new ReproTxn();
        txn.getMsref();
        txn.setMsref("value");
    }
}
