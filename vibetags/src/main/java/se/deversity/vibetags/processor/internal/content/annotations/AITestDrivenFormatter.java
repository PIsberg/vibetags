package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AITestDriven annotations for all platforms.
 */
public final class AITestDrivenFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AITestDriven td = element.getAnnotation(AITestDriven.class);
        if (td == null) return;
        String className = ElementNaming.elementPath(element);
        int coverageGoal = td.coverageGoal();
        String testLocation = td.testLocation();
        String mockPolicy = td.mockPolicy();

        StringBuilder frameworks = new StringBuilder();
        for (AITestDriven.Framework f : td.framework()) {
            if (frameworks.length() > 0) frameworks.append(", ");
            frameworks.append(f.name());
        }
        String frameworksStr = frameworks.toString();

        String locationHint = testLocation.isEmpty() ? "" : " Test file: " + testLocation + ".";
        String mockHint = mockPolicy.isEmpty() ? "" : " Mock policy: " + mockPolicy + ".";
        String summary = "Coverage goal: " + coverageGoal + "%. Framework: " + frameworksStr + "." + locationHint + mockHint;

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <element path=\"").append(className).append("\">\n");
                sb.append("      <coverage_goal>").append(coverageGoal).append("</coverage_goal>\n");
                sb.append("      <frameworks>").append(frameworksStr).append("</frameworks>\n");
                if (!testLocation.isEmpty()) {
                    sb.append("      <test_location>").append(testLocation).append("</test_location>\n");
                }
                if (!mockPolicy.isEmpty()) {
                    sb.append("      <mock_policy>").append(mockPolicy).append("</mock_policy>\n");
                }
                sb.append("    </element>\n");
                break;
            case CODEX:
                sb.append("- **").append(className).append("**: ").append(summary).append("\n");
                break;
            case COPILOT:
                sb.append("- `").append(className).append("` - ").append(summary).append("\n");
                break;
            case QWEN:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case GEMINI:
            case GEMINI_MD:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case LLMS:
                sb.append("- [").append(ElementNaming.elementDisplayName(element)).append("](").append(className).append("): ").append(summary).append("\n");
                break;
            case LLMS_FULL:
                sb.append("### ").append(className).append("\n- **Coverage Goal**: ").append(coverageGoal).append("%\n- **Frameworks**: ").append(frameworksStr).append("\n");
                if (!testLocation.isEmpty()) {
                    sb.append("- **Test Location**: ").append(testLocation).append("\n");
                }
                if (!mockPolicy.isEmpty()) {
                    sb.append("- **Mock Policy**: ").append(mockPolicy).append("\n");
                }
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### TEST-DRIVEN: ").append(className).append("\n- **Rule**: Changes MUST be accompanied by test updates.\n- **Coverage Goal**: ").append(coverageGoal).append("%\n- **Frameworks**: ").append(frameworksStr).append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case MENTAT:
                sb.append("    {\"path\": \"").append(className).append("\", \"coverageGoal\": ").append(coverageGoal).append(", \"frameworks\": \"").append(frameworksStr).append("\"},\n");
                break;
            case SWEEP:
                sb.append("  - \"Test-driven requirement for ").append(className).append(": ").append(summary).append("\"\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (test-driven): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
