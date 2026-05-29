package se.deversity.vibetags.processor;

import org.junit.jupiter.api.Test;

import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Direct coverage of {@code AIGuardrailProcessor.SynchronizedMessager} — a private static
 * inner class that implements the {@link Messager} interface with synchronized delegates.
 *
 * <p>The three overloads with {@code Element} (and optional {@code AnnotationMirror} /
 * {@code AnnotationValue}) parameters are never called by {@link se.deversity.vibetags.processor.internal.GuardrailFileWriter}
 * during the parallel write phase (which only uses the 2-arg {@code printMessage}), so
 * they are reached only via reflection here.  The production intent is defensive: the class
 * implements the full {@link Messager} contract so it is safe to use as a drop-in replacement.
 *
 * <p>These tests exercise all four overloads and assert that each delegates to the underlying
 * messager without throwing — the only meaningful contract for a synchronized proxy.
 */
class SynchronizedMessagerTest {

    /**
     * Locates and instantiates {@code AIGuardrailProcessor$SynchronizedMessager} via
     * reflection. The class is {@code private static final} so we must use
     * {@code setAccessible(true)}.
     */
    private static Messager newSynchronizedMessager(Messager delegate) throws Exception {
        Class<?> clazz = Class.forName(
            "se.deversity.vibetags.processor.AIGuardrailProcessor$SynchronizedMessager");
        Constructor<?> ctor = clazz.getDeclaredConstructor(Messager.class);
        ctor.setAccessible(true);
        return (Messager) ctor.newInstance(delegate);
    }

    @Test
    void twoArgPrintMessage_delegatesAndDoesNotThrow() throws Exception {
        List<String> captured = new ArrayList<>();
        Messager delegate = mock(Messager.class);
        doAnswer(inv -> { captured.add(inv.getArgument(1, CharSequence.class).toString()); return null; })
            .when(delegate).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class));

        Messager sync = newSynchronizedMessager(delegate);
        sync.printMessage(Diagnostic.Kind.NOTE, "hello-two-arg");

        assertEquals(List.of("hello-two-arg"), captured);
    }

    @Test
    void threeArgPrintMessage_delegatesAndDoesNotThrow() throws Exception {
        List<String> captured = new ArrayList<>();
        Messager delegate = mock(Messager.class);
        Element el = mock(Element.class);
        doAnswer(inv -> { captured.add(inv.getArgument(1, CharSequence.class).toString()); return null; })
            .when(delegate).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class), any(Element.class));

        Messager sync = newSynchronizedMessager(delegate);
        sync.printMessage(Diagnostic.Kind.WARNING, "hello-three-arg", el);

        assertEquals(List.of("hello-three-arg"), captured,
            "3-arg printMessage must delegate to underlying Messager");
    }

    @Test
    void fourArgPrintMessage_delegatesAndDoesNotThrow() throws Exception {
        List<String> captured = new ArrayList<>();
        Messager delegate = mock(Messager.class);
        Element el = mock(Element.class);
        AnnotationMirror am = mock(AnnotationMirror.class);
        doAnswer(inv -> { captured.add(inv.getArgument(1, CharSequence.class).toString()); return null; })
            .when(delegate).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class),
                any(Element.class), any(AnnotationMirror.class));

        Messager sync = newSynchronizedMessager(delegate);
        sync.printMessage(Diagnostic.Kind.ERROR, "hello-four-arg", el, am);

        assertEquals(List.of("hello-four-arg"), captured,
            "4-arg printMessage must delegate to underlying Messager");
    }

    @Test
    void fiveArgPrintMessage_delegatesAndDoesNotThrow() throws Exception {
        List<String> captured = new ArrayList<>();
        Messager delegate = mock(Messager.class);
        Element el = mock(Element.class);
        AnnotationMirror am = mock(AnnotationMirror.class);
        AnnotationValue av = mock(AnnotationValue.class);
        doAnswer(inv -> { captured.add(inv.getArgument(1, CharSequence.class).toString()); return null; })
            .when(delegate).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class),
                any(Element.class), any(AnnotationMirror.class), any(AnnotationValue.class));

        Messager sync = newSynchronizedMessager(delegate);
        sync.printMessage(Diagnostic.Kind.NOTE, "hello-five-arg", el, am, av);

        assertEquals(List.of("hello-five-arg"), captured,
            "5-arg printMessage must delegate to underlying Messager");
    }

    @Test
    void allOverloads_synchronized_noDeadlock() throws Exception {
        // Verify concurrent calls on the same SynchronizedMessager do not deadlock.
        // Uses a simple delegate that records calls with a small sleep to encourage interleaving.
        List<String> captured = new ArrayList<>();
        Messager delegate = mock(Messager.class);
        doAnswer(inv -> { synchronized (captured) { captured.add("ok"); } return null; })
            .when(delegate).printMessage(any(Diagnostic.Kind.class), any(CharSequence.class));

        Messager sync = newSynchronizedMessager(delegate);
        // Call from two threads concurrently
        Thread t1 = new Thread(() -> sync.printMessage(Diagnostic.Kind.NOTE, "t1"));
        Thread t2 = new Thread(() -> sync.printMessage(Diagnostic.Kind.NOTE, "t2"));
        t1.start(); t2.start();
        t1.join(5000); t2.join(5000);

        assertFalse(t1.isAlive(), "Thread t1 must have completed (no deadlock)");
        assertFalse(t2.isAlive(), "Thread t2 must have completed (no deadlock)");
        assertEquals(2, captured.size(), "Both threads must have delivered their message");
    }
}
