package com.example.gcomp.lib;

import se.deversity.vibetags.annotations.AILocked;

@AILocked(reason = "Wire format is shared with an independently released build")
public class Codec {
    public byte[] encode(String s) {
        return s.getBytes();
    }
}
