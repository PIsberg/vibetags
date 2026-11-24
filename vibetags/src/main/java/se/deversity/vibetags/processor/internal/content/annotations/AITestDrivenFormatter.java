package se.deversity.vibetags.processor.internal.content.annotations;

// CPD-OFF

import se.deversity.vibetags.processor.model.TaggedElement;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Escape;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AITestDriven annotations for all platforms.
 */
public final class AITestDrivenFormatter implements AnnotationFormatter {
    @Override
    public void render(TaggedElement element, StringBuilder sb, Platform platform) {
        AITestDriven td = element.annotation(AITestDriven.class);
        if (td == null) return;
        String className = element.path();
        int coverageGoal = td.coverageGoal();
        String testLocation = td.testLocation();
        String mockPolicy = td.mockPolicy();
        String frameworksStr = frameworks(td.framework());
        String summary = summary(coverageGoal, frameworksStr, testLocation, mockPolicy);

        if (CommonFormatterHelper.formatStandardPlatform(element, sb, platform, summary)) return;

        switch (platform) {
            case CLAUDE:
                renderClaude(className, coverageGoal, frameworksStr, testLocation, mockPolicy, sb);
                break;
            case LLMS_FULL:
                renderLlmsFull(className, coverageGoal, frameworksStr, testLocation, mockPolicy, sb);
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### TEST-DRIVEN: ").append(className).append("\n- **Rule**: Changes MUST be accompanied by test updates.\n- **Coverage Goal**: ").append(coverageGoal).append("%\n")
                    .append(CommonFormatterHelper.bullet("Frameworks", frameworksStr)).append('\n');
                break;
            case CODERABBIT:
                sb.append("- `").append(className).append("` (test-driven)").append(CommonFormatterHelper.clause(": ", summary)).append('\n');
                break;
            default:
                break;
        }
    }

    private static String frameworks(AITestDriven.Framework[] frameworkList) {
        StringBuilder frameworks = new StringBuilder();
        for (AITestDriven.Framework f : frameworkList) {
            if (frameworks.length() > 0) frameworks.append(", ");
            frameworks.append(f.name());
        }
        return frameworks.toString();
    }

    private static String summary(int coverageGoal, String frameworksStr, String testLocation, String mockPolicy) {
        String locationHint = testLocation.isEmpty() ? "" : " Test file: " + testLocation + ".";
        String mockHint = mockPolicy.isEmpty() ? "" : " Mock policy: " + mockPolicy + ".";
        return "Coverage goal: " + coverageGoal + "%. Framework: " + frameworksStr + "." + locationHint + mockHint;
    }

    private static void renderClaude(String className, int coverageGoal, String frameworksStr,
                                     String testLocation, String mockPolicy, StringBuilder sb) {
        sb.append("    <element path=\"").append(Escape.xml(className)).append("\">\n")
            .append("      <coverage_goal>").append(coverageGoal).append("</coverage_goal>\n")
            .append(CommonFormatterHelper.element("frameworks", frameworksStr));
        if (!testLocation.isEmpty()) {
            sb.append("      <test_location>").append(Escape.xml(testLocation)).append("</test_location>\n");
        }
        if (!mockPolicy.isEmpty()) {
            sb.append("      <mock_policy>").append(Escape.xml(mockPolicy)).append("</mock_policy>\n");
        }
        sb.append("    </element>\n");
    }

    private static void renderLlmsFull(String className, int coverageGoal, String frameworksStr,
                                       String testLocation, String mockPolicy, StringBuilder sb) {
        sb.append("### ").append(className).append("\n- **Coverage Goal**: ").append(coverageGoal).append("%\n")
            .append(CommonFormatterHelper.bullet("Frameworks", frameworksStr));
        if (!testLocation.isEmpty()) {
            sb.append("- **Test Location**: ").append(testLocation).append('\n');
        }
        if (!mockPolicy.isEmpty()) {
            sb.append("- **Mock Policy**: ").append(mockPolicy).append('\n');
        }
        sb.append('\n');
    }
}
