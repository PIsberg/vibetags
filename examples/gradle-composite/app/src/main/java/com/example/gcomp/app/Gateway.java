package com.example.gcomp.app;

import se.deversity.vibetags.annotations.AIContext;

@AIContext(focus = "Request routing", avoids = "Storage details")
public class Gateway {
    public static void main(String[] args) {
        System.out.println("ok");
    }
}
