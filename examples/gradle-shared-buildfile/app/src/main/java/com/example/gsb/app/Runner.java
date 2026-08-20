package com.example.gsb.app;

import se.deversity.vibetags.annotations.AIContext;

@AIContext(focus = "Startup wiring only", avoids = "Domain rules")
public class Runner {
    public static void main(String[] args) {
        System.out.println("ok");
    }
}
