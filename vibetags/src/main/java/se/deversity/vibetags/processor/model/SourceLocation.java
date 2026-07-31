package se.deversity.vibetags.processor.model;

/**
 * Source location of an element declaration: file path plus a 1-based inclusive line range.
 *
 * <p>Best-effort metadata. Positions come from the javac Compiler Tree API, which is unavailable
 * under other compilers (e.g. ECJ) and in in-memory compilation — callers must treat a missing
 * location as normal, never as an error.
 */
public record SourceLocation(String file, long startLine, long endLine) {
}
