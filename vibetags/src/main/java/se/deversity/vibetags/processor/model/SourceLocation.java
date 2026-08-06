package se.deversity.vibetags.processor.model;

/**
 * Source location of an element declaration: file path plus a 1-based inclusive line range.
 *
 * <p>Best-effort metadata. Positions come from the javac Compiler Tree API, which is unavailable
 * under other compilers (e.g. ECJ) and in in-memory compilation — callers must treat a missing
 * location as normal, never as an error.
 *
 * @param file      path to the source file the declaration was read from
 * @param startLine first line of the declaration, 1-based and inclusive
 * @param endLine   last line of the declaration, 1-based and inclusive
 */
public record SourceLocation(String file, long startLine, long endLine) {
}
