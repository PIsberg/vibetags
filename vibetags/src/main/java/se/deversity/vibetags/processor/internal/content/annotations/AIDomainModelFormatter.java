package se.deversity.vibetags.processor.internal.content.annotations;

import javax.lang.model.element.Element;
import se.deversity.vibetags.annotations.AIDomainModel;
import se.deversity.vibetags.processor.internal.ElementNaming;
import se.deversity.vibetags.processor.internal.content.AnnotationFormatter;
import se.deversity.vibetags.processor.internal.content.Platform;

/**
 * Formats @AIDomainModel annotations for all platforms.
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class AIDomainModelFormatter implements AnnotationFormatter {
    @Override
    public void format(Element element, StringBuilder sb, Platform platform) {
        AIDomainModel domainModel = element.getAnnotation(AIDomainModel.class);
        if (domainModel == null) return;
        String className = ElementNaming.elementPath(element);
        String[] allow = domainModel.allow();
        String allowedStr = String.join(", ", allow);
        String summary = "Pure Domain Model. Banned imports: [Spring, JPA, Hibernate, Jackson, etc.]. " 
            + (allow.length > 0 ? "Allowed imports: [" + allowedStr + "]" : "No external framework imports permitted.");

        switch (platform) {
            case CURSOR:
            case WINDSURF:
                sb.append("* `").append(className).append("` - ").append(summary).append("\n");
                break;
            case CLAUDE:
                sb.append("    <file path=\"").append(className).append("\">\n      <domain_model_boundary>Pure Domain Model</domain_model_boundary>\n");
                if (allow.length > 0) {
                    sb.append("      <allowed_imports>").append(allowedStr).append("</allowed_imports>\n");
                }
                sb.append("    </file>\n");
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
                sb.append("### ").append(className).append("\n- **Domain Boundary**: Framework-agnostic Domain Entity.\n");
                if (allow.length > 0) {
                    sb.append("- **Allowed Packages**: ").append(allowedStr).append("\n");
                }
                sb.append("\n");
                break;
            case AIDER_CONVENTIONS:
                sb.append("#### DOMAIN MODEL: ").append(className).append("\n- **Policy**: Pure Domain model, framework-free.\n")
                  .append(allow.length > 0 ? "- **Allowed**: " + allowedStr + "\n" : "").append("\n");
                break;
            case ZED:
                sb.append("- `").append(className).append("`: ").append(summary).append("\n");
                break;
            case INTERPRETER:
                sb.append("- `").append(className).append("` (domain model): ").append(summary).append("\n");
                break;
            default:
                break;
        }
    }
}
