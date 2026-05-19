package se.deversity.vibetags.processor;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import se.deversity.asynctest.AsyncTest;
import se.deversity.vibetags.processor.internal.WriteCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency stress test for {@link WriteCache} using async-test-lib.
 * Verifies that the cache is resilient under concurrent reads, writes, and invalidations.
 */
class WriteCacheAsyncTest {

    private WriteCache cache;
    private Path rootDir;

    @BeforeEach
    void initCache(@TempDir Path tempDir) {
        this.rootDir = tempDir;
        this.cache = new WriteCache(tempDir.resolve(".vibetags-cache"));
    }

    @AsyncTest(threads = 10, invocations = 50, timeoutMs = 5000)
    void testConcurrentCacheOperations() throws IOException {
        String uniqueId = UUID.randomUUID().toString();
        Path file = rootDir.resolve("file-" + uniqueId + ".txt");
        String content = "content-" + uniqueId;
        
        Files.writeString(file, content);

        // Perform concurrent write and read
        cache.recordWrite(file, content);
        
        // Since each thread writes to a completely distinct file, there should be no cross-thread collision on entries.
        // However, if the underlying map is not thread-safe, concurrent reads/writes will trigger exceptions.
        assertTrue(cache.isUnchanged(file, content), "Cache should hit for isolated file");

        // Concurrent invalidation
        cache.invalidate(file);
        assertFalse(cache.isUnchanged(file, content), "Cache should miss after invalidation");
    }
}
