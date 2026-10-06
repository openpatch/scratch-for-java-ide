package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and rewrites {@code scratch4j} marked regions in Java source text.
 *
 * <p>Only the text strictly between the markers is ever managed; everything
 * else in the file is untouchable. Marker syntax:
 *
 * <pre>
 * // scratch4j:begin &lt;id&gt;
 * // scratch4j:end &lt;id&gt;
 * </pre>
 */
public final class RegionParser {

  // The id is a word; the rest of the marker line is free prose (e.g.
  // "// scratch4j:begin fields  (managed by the stage designer)").
  private static final Pattern BEGIN =
      Pattern.compile("^\\s*//\\s*scratch4j:begin\\s+(\\w+).*$", Pattern.MULTILINE);
  private static final Pattern END =
      Pattern.compile("^\\s*//\\s*scratch4j:end\\s+(\\w+).*$", Pattern.MULTILINE);

  /** Thrown when markers are malformed (no end marker, nested or duplicate ids). */
  public static final class RegionParseException extends RuntimeException {
    public RegionParseException(String message) {
      super(message);
    }
  }

  private RegionParser() {}

  /** All regions in the file, in order of appearance. */
  public static List<Region> parse(String source) {
    Objects.requireNonNull(source, "source");
    List<Region> regions = new ArrayList<>();
    Matcher begin = BEGIN.matcher(source);
    Matcher end = END.matcher(source);
    int pos = 0;
    while (begin.find(pos)) {
      String id = begin.group(1);
      // The begin regex's ^\s* may start on an earlier blank line, so derive
      // the marker line (and its indentation) from the match end, which always
      // sits on the line that carries the marker text.
      int markerLineStart = source.lastIndexOf('\n', Math.max(0, begin.end() - 1)) + 1;
      String markerLine = source.substring(markerLineStart, begin.end());
      String indent = markerLine.substring(0, markerLine.length()
          - markerLine.stripLeading().length());
      int bodyStart = begin.end(); // just before the newline of the marker line
      if (source.charAt(bodyStart) == '\r') {
        bodyStart++;
      }
      if (source.charAt(bodyStart) != '\n') {
        throw new RegionParseException(
            "Begin marker for region '" + id + "' must end its line: "
                + source.substring(begin.start(), Math.min(begin.end() + 40, source.length())));
      }
      bodyStart++;
      if (!end.find(bodyStart)) {
        throw new RegionParseException("Missing end marker for region '" + id + "'");
      }
      String endId = end.group(1);
      if (!endId.equals(id)) {
        throw new RegionParseException(
            "End marker '" + endId + "' does not match open region '" + id + "'");
      }
      int bodyEnd = end.start();
      String body = source.substring(bodyStart, bodyEnd);
      if (regions.stream().anyMatch(r -> r.id().equals(id))) {
        throw new RegionParseException("Duplicate region id '" + id + "'");
      }
      regions.add(new Region(id, bodyStart, bodyEnd, body, indent));
      pos = end.end();
    }
    return regions;
  }

  /**
   * Replaces the body of the region with the given id, leaving everything
   * else byte-identical. The new body must not contain marker lines.
   *
   * @throws RegionParseException if the id is unknown or the body is invalid
   */
  public static String rewrite(String source, String id, String newBody) {
    Objects.requireNonNull(newBody, "newBody");
    if (BEGIN.matcher(newBody).find() || END.matcher(newBody).find()) {
      throw new RegionParseException("Region body must not contain scratch4j markers");
    }
    Region region = find(source, id);
    String body = newBody.isEmpty() ? "" : newBody.endsWith("\n") ? newBody : newBody + "\n";
    return source.substring(0, region.beginOffset()) + body + source.substring(region.endOffset());
  }

  /** The region with the given id, or throws if absent. */
  public static Region find(String source, String id) {
    return parse(source).stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new RegionParseException("Unknown region '" + id + "'"));
  }

  /** Whether the region exists (and the file parses). */
  public static boolean has(String source, String id) {
    return parse(source).stream().anyMatch(r -> r.id().equals(id));
  }
}
