package com.example.gflat.lib;

import se.deversity.vibetags.annotations.AILocked;

@AILocked(reason = "On-disk format is depended on by released clients")
public class Store {
    public void put(String key, String value) {
    }
}
