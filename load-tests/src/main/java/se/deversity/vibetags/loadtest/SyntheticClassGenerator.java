package se.deversity.vibetags.loadtest;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates syntactically valid Java source code for a batch of annotated classes.
 *
 * <p>Each generated class is placed in {@code com.example.generated} and is annotated
 * with a rotating mix of all six VibeTags annotations so that the processor exercises
 * every code branch in {@code AIGuardrailProcessor.process()}.
 *
 * <p>The distribution across N classes:
 * <ul>
 *   <li>Every class: {@code @AIContext}
 *   <li>Every 2nd class: {@code @AILocked}
 *   <li>Every 3rd class: {@code @AIAudit}
 *   <li>Every 4th class: {@code @AIIgnore}
 *   <li>Every 5th class: {@code @AIPrivacy} (on a field)
 *   <li>Every 7th class: {@code @AIDraft}
 * </ul>
 */
public final class SyntheticClassGenerator {

    private SyntheticClassGenerator() {}

    /**
     * Returns a list of {@code [className, sourceCode]} pairs for {@code n} classes.
     *
     * @param n number of classes to generate (must be ≥ 1)
     * @return ordered list of {@code String[2]} where {@code [0]} is the simple class name
     *         and {@code [1]} is the complete Java source text
     */
    public static List<String[]> generate(int n) {
        List<String[]> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String className = "SyntheticClass" + i;
            result.add(new String[]{className, buildSource(className, i)});
        }
        return result;
    }

    // -------------------------------------------------------------------------

    private static String buildSource(String className, int index) {
        StringBuilder sb = new StringBuilder(512);

        sb.append("package com.example.generated;\n\n");

        // Imports — always include all, the compiler will ignore unused ones
        sb.append("import se.deversity.vibetags.annotations.AILocked;\n");
        sb.append("import se.deversity.vibetags.annotations.AIContext;\n");
        sb.append("import se.deversity.vibetags.annotations.AIAudit;\n");
        sb.append("import se.deversity.vibetags.annotations.AIIgnore;\n");
        sb.append("import se.deversity.vibetags.annotations.AIPrivacy;\n");
        sb.append("import se.deversity.vibetags.annotations.AIDraft;\n\n");

        // Class-level annotations
        if (index % 2 == 0) {
            sb.append("@AILocked(reason = \"Synthetic locked class #").append(index).append("\")\n");
        }
        sb.append("@AIContext(focus = \"Performance in class #").append(index)
          .append("\", avoids = \"reflection\")\n");
        if (index % 3 == 0) {
            sb.append("@AIAudit(checkFor = {\"SQL Injection\", \"Thread Safety\"})\n");
        }
        if (index % 4 == 0) {
            sb.append("@AIIgnore\n");
        }
        if (index % 7 == 0) {
            sb.append("@AIDraft(instructions = \"Implement method #").append(index).append("\")\n");
        }

        sb.append("public class ").append(className).append(" {\n\n");

        // Field-level @AIPrivacy on every 5th class
        if (index % 5 == 0) {
            sb.append("    @AIPrivacy(reason = \"Contains PII\")\n");
            sb.append("    private String sensitiveField").append(index).append(";\n\n");
        }

        // Simple non-empty body so the compiler produces bytecode
        sb.append("    public int id() { return ").append(index).append("; }\n");
        sb.append("}\n");

        return sb.toString();
    }
}
