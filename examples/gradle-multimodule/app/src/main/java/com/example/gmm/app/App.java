package com.example.gmm.app;

import com.example.gmm.core.IrNode;
import se.deversity.vibetags.annotations.AIContext;

/** Entry point. Contextual rather than locked, so both bucket kinds appear across modules. */
@AIContext(focus = "Wiring only: parsing and rendering live in their own modules",
           avoids = "Business logic")
public class App {

    public static void main(String[] args) {
        System.out.println(new IrNode("root").name());
    }
}
