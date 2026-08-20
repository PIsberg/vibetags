package com.example.gsb.core;

import se.deversity.vibetags.annotations.AILocked;

@AILocked(reason = "Reconciliation order is load-bearing")
public class Ledger {
    public int balance() {
        return 0;
    }
}
