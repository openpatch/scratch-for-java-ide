package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds and rewrites the blocks of Java source that a visual editor owns.
 *
 * <p>Only the text strictly between the markers is ever managed; everything
 * else in the file is untouchable. A block names the editor it belongs to:
 *
 * <pre>
 * // begin stage designer
 * // end stage designer
 * </pre>
 *
 * <p>The editors are {@link #STAGE_DESIGNER}, {@link #SPRITE_EDITOR} and
 * {@link #PROJECT_SETTINGS}. Where a block stands says what it holds, and that
 * is its {@linkplain Region#id() id}: the stage designer's block in the class
 * body is {@code fields}, the one in the constructor {@code setup}; the sprite
 * editor's is {@code setup}; the project settings' block in {@code main} is
 * {@code options}, the one in the window's constructor {@code window}.
 */
public final class RegionParser {

  public static final String STAGE_DESIGNER = "stage designer";
  public static final String SPRITE_EDITOR = "sprite editor";
  public static final String PROJECT_SETTINGS = "project settings";

  private static final String EDITORS = "(stage designer|sprite editor|project settings)";
  private static final Pattern BEGIN =
      Pattern.compile("^[ \\t]*//[ \\t]*begin[ \\t]+" + EDITORS + "[ \\t]*$", Pattern.MULTILINE);
  private static final Pattern END =
      Pattern.compile("^[ \\t]*//[ \\t]*end[ \\t]+" + EDITORS + "[ \\t]*$", Pattern.MULTILINE);
  private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");

  /** Thrown when markers are malformed (no end marker, nested or duplicate ids). */
  public static final class RegionParseException extends RuntimeException {
    public RegionParseException(String message) {
      super(message);
    }
  }

  private RegionParser() {}

  /** The begin marker line of a block, as the editors write it. */
  public static String begin(String indent, String editor) {
    return indent + "// begin " + editor;
  }

  /** The end marker line of a block, as the editors write it. */
  public static String end(String indent, String editor) {
    return indent + "// end " + editor;
  }

  /** All regions in the file, in order of appearance. */
  public static List<Region> parse(String source) {
    Objects.requireNonNull(source, "source");
    List<Region> regions = new ArrayList<>();
    Matcher begin = BEGIN.matcher(source);
    Matcher end = END.matcher(source);
    int[] depth = null;
    int pos = 0;
    while (begin.find(pos)) {
      String editor = begin.group(1);
      int markerLineStart = source.lastIndexOf('\n', Math.max(0, begin.end() - 1)) + 1;
      String markerLine = source.substring(markerLineStart, begin.end());
      String indent = markerLine.substring(0, markerLine.length()
          - markerLine.stripLeading().length());
      int bodyStart = begin.end(); // just before the newline of the marker line
      if (bodyStart < source.length() && source.charAt(bodyStart) == '\r') {
        bodyStart++;
      }
      if (bodyStart >= source.length() || source.charAt(bodyStart) != '\n') {
        throw new RegionParseException("The '// begin " + editor + "' line must end the line");
      }
      bodyStart++;
      if (!end.find(bodyStart)) {
        throw new RegionParseException("'// begin " + editor + "' has no '// end " + editor + "'");
      }
      if (!end.group(1).equals(editor)) {
        throw new RegionParseException(
            "'// end " + end.group(1) + "' closes '// begin " + editor + "'");
      }
      if (depth == null) depth = depths(source);
      String id = id(source, editor, markerLineStart, depth);
      if (regions.stream().anyMatch(r -> r.id().equals(id))) {
        throw new RegionParseException("A second '// begin " + editor + "' block in the same place");
      }
      int bodyEnd = source.lastIndexOf('\n', end.start() - 1) + 1;
      regions.add(new Region(id, editor, bodyStart, bodyEnd,
          source.substring(bodyStart, bodyEnd), indent));
      pos = end.end();
    }
    return regions;
  }

  /** What a block holds, from where it stands. */
  private static String id(String source, String editor, int at, int[] depth) {
    switch (editor) {
      case STAGE_DESIGNER:
        return depth[at] <= 1 ? "fields" : "setup";
      case SPRITE_EDITOR:
        return "setup";
      default:
        return insideMain(source, at, depth) ? "options" : "window";
    }
  }

  /** Whether the position lies in the body of a {@code main} method. */
  private static boolean insideMain(String source, int at, int[] depth) {
    Matcher main = MAIN.matcher(source);
    while (main.find()) {
      int open = source.indexOf('{', main.end());
      if (open < 0 || open > at) continue;
      // the body runs while the depth stays above the one before its brace
      int inside = depth[open] + 1;
      boolean contains = true;
      for (int i = open + 1; i <= at; i++) {
        if (depth[i] < inside) {
          contains = false;
          break;
        }
      }
      if (contains) return true;
    }
    return false;
  }

  /**
   * The brace depth at each position, outside strings, characters and
   * comments: 1 in a class body, 2 or more inside a method.
   */
  static int[] depths(String source) {
    int[] depth = new int[source.length() + 1];
    int d = 0;
    int i = 0;
    while (i < source.length()) {
      char c = source.charAt(i);
      depth[i] = d;
      if (source.startsWith("//", i)) {
        int line = source.indexOf('\n', i);
        int stop = line < 0 ? source.length() : line;
        for (; i < stop; i++) depth[i] = d;
        continue;
      }
      if (source.startsWith("/*", i)) {
        int close = source.indexOf("*/", i + 2);
        int stop = close < 0 ? source.length() : close + 2;
        for (; i < stop; i++) depth[i] = d;
        continue;
      }
      if (source.startsWith("\"\"\"", i)) {
        int close = source.indexOf("\"\"\"", i + 3);
        int stop = close < 0 ? source.length() : close + 3;
        for (; i < stop; i++) depth[i] = d;
        continue;
      }
      if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < source.length() && source.charAt(j) != c && source.charAt(j) != '\n') {
          if (source.charAt(j) == '\\') j++;
          j++;
        }
        int stop = Math.min(source.length(), j + 1);
        for (; i < stop; i++) depth[i] = d;
        continue;
      }
      if (c == '{') d++;
      else if (c == '}') d--;
      i++;
    }
    depth[source.length()] = d;
    return depth;
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
      throw new RegionParseException("A block must not contain begin or end lines");
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
        .orElseThrow(() -> new RegionParseException("No '" + id + "' block"));
  }

  /** Whether the region exists (and the file parses). */
  public static boolean has(String source, String id) {
    return parse(source).stream().anyMatch(r -> r.id().equals(id));
  }
}
