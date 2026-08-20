package com.example.gflat.app;

import se.deversity.vibetags.annotations.AIContext;

@AIContext(focus = "CLI surface only", avoids = "Persistence")
public class Console {
    public static void main(String[] args) {
        System.out.println("ok");
    }
}
