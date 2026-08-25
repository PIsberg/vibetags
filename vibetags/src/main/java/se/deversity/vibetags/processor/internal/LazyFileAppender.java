package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * A file appender that does not touch the filesystem until it has something to write.
 *
 * <p>Logback's {@code FileAppender} opens its file in {@code start()}, so configuring the logger
 * created the file whether or not a single event followed. A build with no annotations, in a
 * project that had opted no platform in, still got a zero-byte {@code vibetags.log} dropped into
 * its working tree: an untracked file the consumer never asked for, needing a {@code .gitignore}
 * entry, containing nothing (#487).
 *
 * <p>Tier-1 invariant 1 is that file presence is the only opt-in and VibeTags never creates an
 * output file. The log is not a guardrail file and the invariant does not name it, but the
 * reasoning carries: a processor asked to look at somebody's codebase and finding nothing to
 * guard should leave no trace of having looked.
 *
 * <p>This extends {@link OutputStreamAppender} rather than {@code FileAppender}, which was the
 * first attempt. Overriding {@code FileAppender.start()} to skip the file open also skips the
 * base-class initialisation it performs on the way past, and the first event then dies on a null
 * {@code reentryGuard}. Deferring the <em>stream</em> instead leaves every lifecycle hook intact:
 * Logback starts and stops a fully configured appender, and the file comes into being on the
 * first byte written to it.
 */
public final class LazyFileAppender extends OutputStreamAppender<ILoggingEvent> {

    private String file = "";
    private boolean append = true;
    private @Nullable DeferredFileStream stream;

    /** The log file to open on first write. Mirrors {@code FileAppender.setFile}. */
    public void setFile(String file) {
        this.file = file;
    }

    /** Append rather than truncate, as the file appender this replaces did. */
    public void setAppend(boolean append) {
        this.append = append;
    }

    /** True once an event has actually been written, which is when the file comes into being. */
    public boolean hasOpenedFile() {
        return stream != null && stream.opened;
    }

    @Override
    public void start() {
        if (file.isEmpty()) {
            addError("no log file set on the appender named [" + getName() + "]");
            return;
        }
        stream = new DeferredFileStream(file, append, this);
        // setOutputStream before start(): OutputStreamAppender expects a stream to be present,
        // and this one is real. It simply has not opened anything yet.
        setOutputStream(stream);
        super.start();
    }

    /**
     * An {@link OutputStream} that creates its file, and any missing parent directories, on the
     * first byte written and not before.
     *
     * <p>A failure to open is reported through the appender and then swallowed: logging must
     * never be the reason somebody's build fails, which is the contract the rest of
     * {@code VibeTagsLogger} holds to.
     */
    private static final class DeferredFileStream extends OutputStream {

        private final String path;
        private final boolean append;
        private final OutputStreamAppender<?> owner;
        private @Nullable OutputStream delegate;
        private boolean failed;
        // volatile: written under the lock in open(), read without it by
        // hasOpenedFile(), which exists so a test can ask whether the file was ever
        // created without forcing it into being.
        private volatile boolean opened;

        DeferredFileStream(String path, boolean append, OutputStreamAppender<?> owner) {
            this.path = path;
            this.append = append;
            this.owner = owner;
        }

        private synchronized @Nullable OutputStream open() {
            if (opened || failed) {
                return delegate;
            }
            try {
                Path target = Paths.get(path);
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                delegate = append
                    ? Files.newOutputStream(target, StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND)
                    : Files.newOutputStream(target, StandardOpenOption.CREATE,
                                            StandardOpenOption.TRUNCATE_EXISTING);
                opened = true;
            } catch (IOException | RuntimeException e) {
                failed = true;
                owner.addError("could not open the VibeTags log file [" + path + "]", e);
            }
            return delegate;
        }

        @Override
        public void write(int b) throws IOException {
            OutputStream out = open();
            if (out != null) {
                out.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            OutputStream out = open();
            if (out != null) {
                out.write(b, off, len);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            // Synchronized like open(): Logback flushes on every event and closes from the
            // appender lifecycle, which is a different thread from the one that may still be
            // opening. Deliberately does not open, because flushing a stream nothing was
            // written to must not
            // create the file.
            if (delegate != null) {
                delegate.flush();
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (delegate != null) {
                delegate.close();
            }
        }
    }
}
