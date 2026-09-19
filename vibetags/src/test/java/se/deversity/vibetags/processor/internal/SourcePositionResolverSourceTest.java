package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.vibetags.processor.model.SourceLocation;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A processing environment that is not javac's has no Tree API, so {@code .vibetags-locks} had no
 * line ranges under it. One that implements {@link SourcePositionResolver.Source} supplies its own
 * (vibetags-ksp does, for Kotlin sources on KSP, #757); the resolver uses them, and reports paths
 * relative to the VibeTags root the way it reports javac's.
 */
class SourcePositionResolverSourceTest {

    @TempDir
    Path root;

    @Test
    void anEnvironmentThatIsASourceSuppliesPositionsRelativeToTheRoot() {
        Element element = mock(Element.class);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class,
            withSettings().extraInterfaces(SourcePositionResolver.Source.class));
        String absolute = root.resolve("src/main/kotlin/com/a/Vault.kt").toString();
        when(((SourcePositionResolver.Source) env).locate(element)).thenReturn(new SourceLocation(absolute, 3, 9));

        SourceLocation location = SourcePositionResolver.forEnv(env, root).resolve(element);

        assertEquals(new SourceLocation("src/main/kotlin/com/a/Vault.kt", 3, 9), location);
    }

    @Test
    void aPathOutsideTheRootIsReportedAsGiven() {
        Element element = mock(Element.class);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class,
            withSettings().extraInterfaces(SourcePositionResolver.Source.class));
        when(((SourcePositionResolver.Source) env).locate(element))
            .thenReturn(new SourceLocation("/elsewhere/Vault.kt", 1, 2));

        assertEquals(new SourceLocation("/elsewhere/Vault.kt", 1, 2),
            SourcePositionResolver.forEnv(env, root).resolve(element));
    }

    @Test
    void aSourceThatThrowsDegradesToNoPosition() {
        Element element = mock(Element.class);
        ProcessingEnvironment env = mock(ProcessingEnvironment.class,
            withSettings().extraInterfaces(SourcePositionResolver.Source.class));
        when(((SourcePositionResolver.Source) env).locate(element)).thenThrow(new IllegalStateException("boom"));

        assertNull(SourcePositionResolver.forEnv(env, root).resolve(element));
    }

    @Test
    void anEnvironmentThatIsNeitherJavacNorASourceStillHasNoPositions() {
        ProcessingEnvironment env = mock(ProcessingEnvironment.class);

        assertNull(SourcePositionResolver.forEnv(env, root).resolve(mock(Element.class)));
    }
}
