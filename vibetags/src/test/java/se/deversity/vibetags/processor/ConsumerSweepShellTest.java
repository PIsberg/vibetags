package se.deversity.vibetags.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The consumer-sweep tests run wherever a usable shell exists, and never skip silently under CI (#744). */
@DisplayName("The consumer-sweep tests find a shell, and fail rather than skip under CI without one")
class ConsumerSweepShellTest {

    @Test
    @DisplayName("sh on PATH is used as is")
    void shOnPathIsUsed() {
        assertEquals(Optional.of("sh"), ConsumerSweepShell.choose(true, Optional.empty()));
    }

    @Test
    @DisplayName("without sh on PATH, Git for Windows' bin/sh.exe is used")
    void gitForWindowsShellIsTheFallback(@TempDir Path gitRoot) throws Exception {
        Path sh = Files.createDirectories(gitRoot.resolve("bin")).resolve("sh.exe");
        Files.writeString(sh, "");
        assertEquals(Optional.of(sh.toAbsolutePath().toString()),
            ConsumerSweepShell.choose(false, Optional.of(gitRoot)));
    }

    @Test
    @DisplayName("usr/bin/sh.exe alone is not used, because it resolves none of the script's tools")
    void usrBinShellAloneIsNotUsed(@TempDir Path gitRoot) throws Exception {
        Files.writeString(Files.createDirectories(gitRoot.resolve("usr/bin")).resolve("sh.exe"), "");
        assertEquals(Optional.empty(), ConsumerSweepShell.choose(false, Optional.of(gitRoot)));
        assertEquals(Optional.empty(), ConsumerSweepShell.choose(false, Optional.empty()));
    }

    @Test
    @DisplayName("the Git for Windows root is three levels above its exec path, and nothing else is guessed")
    void gitRootIsDerivedOnlyFromTheWindowsLayout() {
        assertEquals(Optional.of(Path.of("C:/Program Files/Git")),
            ConsumerSweepShell.rootFromExecPath("C:/Program Files/Git/mingw64/libexec/git-core" + System.lineSeparator()));
        assertEquals(Optional.empty(), ConsumerSweepShell.rootFromExecPath("/usr/lib/git-core"));
    }

    @Test
    @DisplayName("a missing shell fails the test under CI and skips it elsewhere")
    void missingShellFailsUnderCiAndSkipsElsewhere() {
        assertThrows(AssertionFailedError.class, () -> ConsumerSweepShell.skipOrFail(true));
        assertThrows(TestAbortedException.class, () -> ConsumerSweepShell.skipOrFail(false));
    }
}
