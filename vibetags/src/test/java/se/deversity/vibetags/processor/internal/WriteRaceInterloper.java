package se.deversity.vibetags.processor.internal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plays the other writer in a race on one file, driven by {@link GuardrailFileWriter}'s own DEBUG
 * events, which it logs on the writing thread at fixed points: {@code write.commit} immediately
 * before the move into place, {@code write.retry} when it starts over (#1087). Creating the target
 * at the commit puts a file where the writer found none, with no timing involved.
 */
final class WriteRaceInterloper extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final Logger logger;
    private final Path target;
    private final String content;
    private boolean deleteOnRetry;

    private WriteRaceInterloper(Logger logger, Path target, String content) {
        this.logger = logger;
        this.target = target;
        this.content = content;
    }

    /** Attaches to {@code logger} an interloper that creates {@code target} with {@code content} at each commit that finds it absent. */
    static WriteRaceInterloper createOnCommit(Logger logger, Path target, String content) {
        WriteRaceInterloper interloper = new WriteRaceInterloper(logger, target, content);
        interloper.setContext(logger.getLoggerContext());
        interloper.start();
        logger.addAppender(interloper);
        return interloper;
    }

    /** Also deletes the target whenever the writer starts over, so every attempt finds no file. */
    WriteRaceInterloper deletingOnRetry() {
        deleteOnRetry = true;
        return this;
    }

    @Override
    protected void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        try {
            if (message.startsWith("write.commit ") && Files.notExists(target)) {
                Files.writeString(target, content);
            } else if (message.startsWith("write.retry ") && deleteOnRetry) {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        logger.detachAppender(this);
        stop();
    }
}
