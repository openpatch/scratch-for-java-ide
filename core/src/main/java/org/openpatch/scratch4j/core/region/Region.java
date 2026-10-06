package org.openpatch.scratch4j.core.region;

/**
 * A marked region inside a generated-or-managed part of a Java source file,
 * delimited by
 *
 * <pre>
 * // scratch4j:begin fields
 * ...
 * // scratch4j:end fields
 * </pre>
 *
 * <p>The body is the exact text between the two marker lines (including the
 * newline that terminates the last body line). Rewriting a region with its own
 * body is a no-op: parsing and writing round-trip losslessly.
 */
public final class Region {

  private final String id;
  private final int beginOffset;
  private final int endOffset;
  private final String body;
  /** The indentation of the begin marker line - generated bodies reuse it. */
  private final String indent;

  Region(String id, int beginOffset, int endOffset, String body, String indent) {
    this.id = id;
    this.beginOffset = beginOffset;
    this.endOffset = endOffset;
    this.body = body;
    this.indent = indent;
  }

  /** The indentation of the begin marker line (may be empty). */
  public String indent() {
    return indent;
  }

  public String id() {
    return id;
  }

  /** Character offset just after the begin marker line's newline. */
  public int beginOffset() {
    return beginOffset;
  }

  /** Character offset of the start of the end marker line. */
  public int endOffset() {
    return endOffset;
  }

  /** Exact text between the markers, including the trailing newline. */
  public String body() {
    return body;
  }

  @Override
  public String toString() {
    return "Region[" + id + "]";
  }
}
