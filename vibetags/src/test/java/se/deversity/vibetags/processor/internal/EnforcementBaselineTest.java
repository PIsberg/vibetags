package se.deversity.vibetags.processor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The committed record of what enforced elements looked like when they were last approved.
 *
 * <p>This file is checked in, hand-reviewed, and edited by two different modules' builds. Its two
 * failure modes are opposite and both silent. Read too eagerly, a line the format does not actually
 * define becomes an approved signature nobody approved, and enforcement passes a change it should
 * have stopped. Written carelessly, one module's compile drops another module's approvals, and the
 * next build of that sibling reports every one of its enforced elements as changed — a wall of
 * violations caused by nothing the sibling did.
 *
 * <p>The read side is exercised against the shapes a hand-edited or half-written file takes; the
 * write side against the sibling-preservation property, which is the one a reactor depends on and
 * the one no single-module test can see.
 */
class EnforcementBaselineTest {

    private static Path writeBaseline(Path root, String... lines) throws IOException {
        Path file = root.resolve(EnforcementBaseline.FILE_NAME);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /** One baseline line: module, family, element path, signature. */
    private static String line(String module, String family, String path, String signature) {
        return module + "\t" + family + "\t" + path + "\t" + signature;
    }

    @Test
    void anAbsentBaselineLoadsEmptyAndReportsItself(@TempDir Path root) throws IOException {
        assertFalse(EnforcementBaseline.exists(root));
        EnforcementBaseline baseline = EnforcementBaseline.load(root);
        assertTrue(baseline.hasNothingFor("core"));
        assertEquals(Set.of(), baseline.approvedFor("core", Set.of("AIContract")));
    }

    @Test
    void aRecordedSignatureIsReadBack(@TempDir Path root) throws IOException {
        writeBaseline(root,
            "# format: 1",
            line("core", "AIContract", "com.example.Ledger#charge(int)", "charge(int):boolean"));

        EnforcementBaseline baseline = EnforcementBaseline.load(root);
        assertTrue(EnforcementBaseline.exists(root));
        assertFalse(baseline.hasNothingFor("core"));
        assertEquals("charge(int):boolean",
            baseline.signatureFor("core", "AIContract", "com.example.Ledger#charge(int)"));
    }

    @Test
    void commentsAndBlankLinesAreNotEntries(@TempDir Path root) throws IOException {
        writeBaseline(root,
            "# The header this file is written with",
            "",
            "   ",
            "# format: 1");

        assertTrue(EnforcementBaseline.load(root).hasNothingFor("core"),
            "the header must not read back as an approval");
    }

    @Test
    void aLineWithNoTabIsSkippedRatherThanReadAsAnApproval(@TempDir Path root) throws IOException {
        // A hand-edit that lost the tabs, or a half-written line. Admitting it would put a key with
        // no signature into the map, and enforcement would compare against nothing.
        writeBaseline(root,
            "this line has no tabs at all",
            line("core", "AIContract", "com.example.Ledger#charge(int)", "charge(int):boolean"));

        EnforcementBaseline baseline = EnforcementBaseline.load(root);
        assertEquals(Set.of("AIContract\tcom.example.Ledger#charge(int)"),
            baseline.approvedFor("core", Set.of("AIContract")),
            "the junk line is dropped and the real one survives");
    }

    @Test
    void anUnreadableBaselineLoadsEmptyRatherThanThrowing(@TempDir Path root) throws IOException {
        // A directory where the file belongs: present to exists(), impossible to read.
        Files.createDirectories(root.resolve(EnforcementBaseline.FILE_NAME));

        EnforcementBaseline baseline = EnforcementBaseline.load(root);
        assertTrue(baseline.hasNothingFor("core"),
            "a baseline that cannot be read approves nothing, and must not fail the compile");
        assertFalse(EnforcementBaseline.exists(root),
            "a directory is not a baseline file");
    }

    /**
     * A file that is there but cannot be decoded is the other read-side failure: reading it as
     * empty told the enforcer "nothing recorded", and the gate went quiet with a warning. One
     * Cp1252 byte from a Windows editor is all it takes, so the reader must say so, not shrug.
     */
    @Test
    void aBaselineWithAByteThatIsNotUtf8IsReportedRatherThanReadAsEmpty(@TempDir Path root)
            throws IOException {
        byte[] bytes = ("# format: 1\n"
            + line("core", "AIContract", "com.example.Ledger#charge(int)", "charge(int):boolean")
            + "\n").getBytes(StandardCharsets.UTF_8);
        bytes[bytes.length - 2] = (byte) 0xE5;
        Files.write(root.resolve(EnforcementBaseline.FILE_NAME), bytes);

        assertThrows(IOException.class, () -> EnforcementBaseline.load(root),
            "a corrupt baseline must not load as an empty one");
        assertThrows(IOException.class, () -> EnforcementBaseline.exists(root),
            "nor be reported as absent");
    }

    @Test
    void oneModulesApprovalsAreInvisibleToAnother(@TempDir Path root) throws IOException {
        writeBaseline(root,
            line("core", "AIContract", "com.example.Ledger#charge(int)", "charge(int):boolean"),
            line("app", "AIContract", "com.example.Handler#handle()", "handle():void"));

        EnforcementBaseline baseline = EnforcementBaseline.load(root);
        assertEquals(Set.of("AIContract\tcom.example.Ledger#charge(int)"),
            baseline.approvedFor("core", Set.of("AIContract")));
        assertEquals(Set.of("AIContract\tcom.example.Handler#handle()"),
            baseline.approvedFor("app", Set.of("AIContract")));
        assertNull(baseline.signatureFor("app", "AIContract", "com.example.Ledger#charge(int)"),
            "a module must not read a sibling's approval as its own");
    }

    @Test
    void approvalsOutsideTheRequestedFamiliesAreNotReturned(@TempDir Path root) throws IOException {
        // Enforcement is opted into per family. An element approved under @AIPublicAPI must not be
        // reported as an abandoned @AIContract when only @AIContract is being enforced.
        writeBaseline(root,
            line("core", "AIContract", "com.example.Ledger#charge(int)", "charge(int):boolean"),
            line("core", "AIPublicAPI", "com.example.Api", "extends[] members[]"));

        EnforcementBaseline baseline = EnforcementBaseline.load(root);
        assertEquals(Set.of("AIContract\tcom.example.Ledger#charge(int)"),
            baseline.approvedFor("core", Set.of("AIContract")));
        assertEquals(2, baseline.approvedFor("core",
            Set.of("AIContract", "AIPublicAPI")).size());
    }

    @Test
    void aModuleWhoseIdIsAPrefixOfAnothersIsNotConfused(@TempDir Path root) throws IOException {
        // 'core' and 'core-api' are ordinary sibling names, and a startsWith on the bare id would
        // hand one module the other's approvals.
        writeBaseline(root,
            line("core", "AIContract", "com.example.A#a()", "a():void"),
            line("core-api", "AIContract", "com.example.B#b()", "b():void"));

        EnforcementBaseline baseline = EnforcementBaseline.load(root);
        assertEquals(Set.of("AIContract\tcom.example.A#a()"),
            baseline.approvedFor("core", Set.of("AIContract")));
        assertFalse(baseline.hasNothingFor("core-api"));
    }

    @Test
    void updateReplacesThisModulesLinesAndPreservesEverySiblings(@TempDir Path root)
            throws IOException {
        writeBaseline(root,
            line("core", "AIContract", "com.example.Ledger#charge(int)", "charge(int):boolean"),
            line("app", "AIContract", "com.example.Handler#handle()", "handle():void"));

        Map<String, String> current = new LinkedHashMap<>();
        current.put(EnforcementBaseline.familyAndPath("AIContract", "com.example.Ledger#charge(long)"),
            "charge(long):boolean");
        EnforcementBaseline.load(root).update(root, "core", current);

        EnforcementBaseline reread = EnforcementBaseline.load(root);
        assertEquals(Set.of("AIContract\tcom.example.Ledger#charge(long)"),
            reread.approvedFor("core", Set.of("AIContract")),
            "the compiling module's lines are replaced wholesale, not merged");
        assertEquals(Set.of("AIContract\tcom.example.Handler#handle()"),
            reread.approvedFor("app", Set.of("AIContract")),
            "a sibling that did not compile keeps every approval; dropping them would report all "
                + "of its enforced elements as changed on its next build");
    }

    @Test
    void aConstructorIsKeyedUnderInitSoItCannotCollideWithANamesakeMethod() {
        assertEquals("com.acme.Foo.<init>(java.lang.String)",
            EnforcementBaseline.constructorPath("com.acme.Foo.Foo(java.lang.String)"),
            "javac renders both a constructor and a method named like the class under that name, "
                + "and one key for two elements leaves one of them unenforceable");
        assertEquals("com.acme.Outer.Inner.<init>(int)",
            EnforcementBaseline.constructorPath("com.acme.Outer.Inner.Inner(int)"),
            "a nested class's constructor is keyed under the nested class, not the outer one");
        assertEquals("com.acme.Foo.<init>(java.util.List<T>)",
            EnforcementBaseline.constructorPath("com.acme.Foo.<T>Foo(java.util.List<T>)"),
            "a generic constructor drops its type parameters here; two constructors of one class "
                + "cannot share a parameter list, so the key stays unique");
    }

    @Test
    void aPathWithNoParameterListIsLeftAlone() {
        assertEquals("com.acme.Foo",
            EnforcementBaseline.constructorPath("com.acme.Foo"),
            "nothing that is not an executable may be rewritten by the constructor rule");
        assertEquals("Foo(int)", EnforcementBaseline.constructorPath("Foo(int)"),
            "a path with no package is returned unchanged rather than half-rewritten");
    }

    @Test
    void updateMergesWhatIsOnDiskNowRatherThanWhatWasLoaded(@TempDir Path root) throws IOException {
        // Both modules of a reactor load the baseline before either has written: the shape of
        // `mvn -T` with two enforcing modules recording against one shared root.
        EnforcementBaseline core = EnforcementBaseline.load(root);
        EnforcementBaseline web = EnforcementBaseline.load(root);

        Map<String, String> webCurrent = new LinkedHashMap<>();
        webCurrent.put(EnforcementBaseline.familyAndPath("AIContract", "com.example.Web#serve()"),
            "serve():void");
        web.update(root, "web", webCurrent);

        Map<String, String> coreCurrent = new LinkedHashMap<>();
        coreCurrent.put(EnforcementBaseline.familyAndPath("AIContract", "com.example.Ledger#charge(int)"),
            "charge(int):boolean");
        core.update(root, "core", coreCurrent);

        EnforcementBaseline reread = EnforcementBaseline.load(root);
        assertEquals(Set.of("AIContract	com.example.Web#serve()"),
            reread.approvedFor("web", Set.of("AIContract")),
            "the second writer merged into the snapshot it loaded before the sibling wrote, so the "
                + "sibling's approvals are gone and its next enforcing build reports every guarded "
                + "element as unrecorded");
        assertEquals(Set.of("AIContract	com.example.Ledger#charge(int)"),
            reread.approvedFor("core", Set.of("AIContract")));
    }

    @Test
    void updateWritesASortedFileWithItsHeader(@TempDir Path root) throws IOException {
        Map<String, String> current = new LinkedHashMap<>();
        current.put(EnforcementBaseline.familyAndPath("AIContract", "com.example.Z#z()"), "z():void");
        current.put(EnforcementBaseline.familyAndPath("AIContract", "com.example.A#a()"), "a():void");
        EnforcementBaseline.load(root).update(root, "core", current);

        List<String> lines = Files.readAllLines(
            root.resolve(EnforcementBaseline.FILE_NAME), StandardCharsets.UTF_8);
        assertTrue(lines.get(0).startsWith("#"), "the file explains itself to a reviewer: " + lines);

        List<String> entries = lines.stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
        assertEquals(entries.stream().sorted().toList(), entries,
            "an unsorted baseline produces a diff whose order depends on the JVM: " + entries);
    }

    @Test
    void updateIsIdempotentSoAnUnchangedModuleProducesNoDiff(@TempDir Path root) throws IOException {
        Map<String, String> current = new LinkedHashMap<>();
        current.put(EnforcementBaseline.familyAndPath("AIContract", "com.example.A#a()"), "a():void");

        EnforcementBaseline.load(root).update(root, "core", current);
        String first = Files.readString(root.resolve(EnforcementBaseline.FILE_NAME), StandardCharsets.UTF_8);
        EnforcementBaseline.load(root).update(root, "core", current);
        String second = Files.readString(root.resolve(EnforcementBaseline.FILE_NAME), StandardCharsets.UTF_8);

        assertEquals(first, second,
            "this file is committed; a build that rewrites it unchanged puts it in every commit");
    }

    @Test
    void updateWaitsForALockHeldElsewhereInThisJvm(@TempDir Path root) throws Exception {
        // #911. A FileLock belongs to the whole JVM, so a sibling module whose processor was loaded
        // by another classloader, with its own copy of the per-root monitor, meets this lock as
        // OverlappingFileLockException. Holding it here, outside the monitor, is that sibling.
        Map<String, String> current = new LinkedHashMap<>();
        current.put(EnforcementBaseline.familyAndPath("AIContract", "com.example.A#a()"), "a():void");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Path file = root.resolve(EnforcementBaseline.FILE_NAME);
        try (FileChannel channel = FileChannel.open(root.resolve(EnforcementBaseline.LOCK_FILE_NAME),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock sibling = channel.lock()) {
            assertTrue(sibling.isValid(), "precondition: the sibling holds the baseline lock");
            Thread recorder = new Thread(() -> {
                try {
                    EnforcementBaseline.load(root).update(root, "core", current);
                } catch (Throwable e) {
                    failure.set(e);
                }
                done.countDown();
            });
            recorder.start();
            assertFalse(done.await(300, TimeUnit.MILLISECONDS),
                "update() ran its read-merge-write while a sibling in this JVM held the lock, "
                    + "which is the lost-update race of #554 for any reactor whose modules load "
                    + "the processor in separate classloaders");
            assertFalse(Files.exists(file), "nothing may be written while the sibling holds the lock");
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "update() must proceed once the lock is free");
        assertNull(failure.get(), () -> "update() failed: " + failure.get());
        assertEquals("a():void", EnforcementBaseline.load(root)
            .signatureFor("core", "AIContract", "com.example.A#a()"));
    }

    @Test
    void twoClassloadersRecordingAtOnceKeepEachOthersApprovals(@TempDir Path root) throws Exception {
        // #911 as it happens in a build: two copies of EnforcementBaseline, each with its own
        // per-root monitor, recording sibling modules into one root at the same moment.
        String element = "com.example.A#a()";
        String key = EnforcementBaseline.familyAndPath("AIContract", element);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (URLClassLoader coreLoader = ownLoader(); URLClassLoader webLoader = ownLoader()) {
            Method[] core = baselineMethods(coreLoader);
            Method[] web = baselineMethods(webLoader);
            for (int round = 0; round < 150; round++) {
                CyclicBarrier start = new CyclicBarrier(2);
                String coreSig = "core" + round;
                String webSig = "web" + round;
                Future<?> a = pool.submit(() -> record(core, start, root, "core", Map.of(key, coreSig)));
                Future<?> b = pool.submit(() -> record(web, start, root, "web", Map.of(key, webSig)));
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
                EnforcementBaseline after = EnforcementBaseline.load(root);
                assertEquals(coreSig, after.signatureFor("core", "AIContract", element),
                    "round " + round + ": web's write dropped core's approval");
                assertEquals(webSig, after.signatureFor("web", "AIContract", element),
                    "round " + round + ": core's write dropped web's approval");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** A loader that defines the processor's classes again rather than delegating to the test's. */
    private static URLClassLoader ownLoader() {
        URL[] path = {
            EnforcementBaseline.class.getProtectionDomain().getCodeSource().getLocation(),
            org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation(),
        };
        return new URLClassLoader(path, ClassLoader.getPlatformClassLoader());
    }

    /** {@code load} and {@code update} of the EnforcementBaseline copy that {@code loader} defines. */
    private static Method[] baselineMethods(ClassLoader loader) throws Exception {
        Class<?> type = loader.loadClass(EnforcementBaseline.class.getName());
        assertFalse(type == EnforcementBaseline.class, "precondition: a second copy of the class");
        return new Method[] {
            type.getMethod("load", Path.class),
            type.getMethod("update", Path.class, String.class, Map.class),
        };
    }

    private static Void record(Method[] baseline, CyclicBarrier start, Path root, String module,
                               Map<String, String> current) throws Exception {
        Object loaded = baseline[0].invoke(null, root);
        start.await(10, TimeUnit.SECONDS);
        baseline[1].invoke(loaded, root, module, current);
        return null;
    }
}
