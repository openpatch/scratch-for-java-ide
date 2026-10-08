package org.openpatch.scratch4j.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one-click fixes behind the light bulb for the errors students hit most:
 * a missing semicolon, a missing import, a misspelled (or wrongly cased) name
 * with one clear candidate, and a {@code while (true)} wrapper in {@code run()}.
 * Each fix is an id the problems pane labels, plus the data {@link #apply}
 * needs; a fix is offered only when the edit is certain, and {@link #apply}
 * returns null when the code changed since so that it no longer fits.
 */
public final class QuickFixes {

  /** Insert {@code ;} where javac expected it. */
  public static final String SEMICOLON = "semicolon";
  /** Add the import line (the fix data) at the top of the file. */
  public static final String IMPORT = "import";
  /** Replace the unknown name at the error with the one suggestion (the fix data). */
  public static final String RENAME = "rename";
  /** Remove a {@code while (true) {} } wrapper: the library already repeats run(). */
  public static final String FOREVER = "forever";

  private static final Pattern FOREVER_HEADER = Pattern.compile(
      "^(\\s*)(?:while\\s*\\(\\s*true\\s*\\)|for\\s*\\(\\s*;\\s*;\\s*\\))\\s*\\{\\s*$");
  private static final Pattern CLOSING = Pattern.compile("^\\s*\\}\\s*$");
  private static final Pattern IMPORT_LINE = Pattern.compile("^\\s*import\\s+");
  private static final Pattern PACKAGE_LINE = Pattern.compile("^\\s*package\\s+");

  private QuickFixes() {}

  /**
   * The name to replace a misspelled identifier with, or null when none is
   * certain: a suggestion that differs only in case wins (Java is
   * case-sensitive); otherwise the closest suggestion must be one typo away
   * and clearly closer than the next one.
   */
  public static String pickRename(String name, List<String> suggestions) {
    if (name == null || name.isEmpty() || suggestions == null || suggestions.isEmpty()) {
      return null;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    int runnerUp = Integer.MAX_VALUE;
    for (String s : suggestions) {
      if (s.equals(name)) {
        continue;
      }
      int distance = DidYouMean.distance(lower, s.toLowerCase(Locale.ROOT));
      if (distance < bestDistance) {
        runnerUp = bestDistance;
        bestDistance = distance;
        best = s;
      } else if (distance < runnerUp) {
        runnerUp = distance;
      }
    }
    return best != null && bestDistance <= 1 && runnerUp > bestDistance ? best : null;
  }

  /** Whether the loop starting at {@code line} (1-based) is a wrapper {@link #FOREVER} removes. */
  public static boolean canRemoveForever(String source, long line) {
    return foreverEnd(source.split("\n", -1), line) >= 0;
  }

  /**
   * The source after the fix, or null when it no longer applies. {@code line}
   * and {@code column} are javac's (1-based; column 0 = unknown).
   */
  public static String apply(String source, String fix, long line, long column, String data) {
    if (source == null || fix == null) {
      return null;
    }
    return switch (fix) {
      case SEMICOLON -> semicolon(source, line, column);
      case IMPORT -> addImport(source, data);
      case RENAME -> rename(source, line, column, data);
      case FOREVER -> removeForever(source, line);
      default -> null;
    };
  }

  private static String semicolon(String source, long line, long column) {
    String[] lines = source.split("\n", -1);
    if (line < 1 || line > lines.length) {
      return null;
    }
    String text = lines[(int) line - 1];
    String code = text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    int at = column >= 1 ? (int) Math.min(column - 1, code.length()) : code.stripTrailing().length();
    if (code.isBlank() || code.substring(0, at).stripTrailing().endsWith(";")) {
      return null;
    }
    // javac points right after the last token: keep that, but never inside trailing spaces
    int end = at;
    while (end > 0 && Character.isWhitespace(code.charAt(end - 1))) {
      end--;
    }
    lines[(int) line - 1] = code.substring(0, end) + ";" + code.substring(end)
        + (text.endsWith("\r") ? "\r" : "");
    return String.join("\n", lines);
  }

  private static String addImport(String source, String importLine) {
    if (importLine == null || importLine.isBlank()) {
      return null;
    }
    String statement = importLine.strip();
    if (!statement.endsWith(";")) {
      statement += ";";
    }
    String[] lines = source.split("\n", -1);
    int lastImport = -1;
    int packageLine = -1;
    for (int i = 0; i < lines.length; i++) {
      String l = lines[i];
      if (l.strip().equals(statement)) {
        return null; // already there
      }
      if (IMPORT_LINE.matcher(l).lookingAt()) {
        lastImport = i;
      } else if (PACKAGE_LINE.matcher(l).lookingAt()) {
        packageLine = i;
      }
    }
    List<String> out = new ArrayList<>(List.of(lines));
    if (lastImport >= 0) {
      out.add(lastImport + 1, statement);
    } else if (packageLine >= 0) {
      out.add(packageLine + 1, "");
      out.add(packageLine + 2, statement);
    } else {
      out.add(0, statement);
      out.add(1, "");
    }
    return String.join("\n", out);
  }

  private static String rename(String source, long line, long column, String replacement) {
    if (replacement == null || replacement.isBlank()) {
      return null;
    }
    String[] lines = source.split("\n", -1);
    if (line < 1 || line > lines.length) {
      return null;
    }
    String text = lines[(int) line - 1];
    int start = (int) column - 1;
    if (start < 0 || start >= text.length()) {
      return null;
    }
    // for this.mvoe javac points at the dot
    if (text.charAt(start) == '.') {
      start++;
    }
    int end = start;
    while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
      end++;
    }
    if (end == start) {
      return null;
    }
    lines[(int) line - 1] = text.substring(0, start) + replacement + text.substring(end);
    return String.join("\n", lines);
  }

  /**
   * Removes the loop header at {@code line} and its closing brace, moving the
   * body one indentation step out. Only a header that is alone on its line
   * and ends with {@code {} is handled, so the edit is predictable.
   */
  private static String removeForever(String source, long line) {
    String[] lines = source.split("\n", -1);
    int end = foreverEnd(lines, line);
    if (end < 0) {
      return null;
    }
    int start = (int) line - 1;
    Matcher header = FOREVER_HEADER.matcher(strip(lines[start]));
    if (!header.matches()) {
      return null;
    }
    String outer = header.group(1);
    String inner = null;
    for (int i = start + 1; i < end; i++) {
      if (!lines[i].isBlank()) {
        inner = leadingSpace(lines[i]);
        break;
      }
    }
    String remove = inner != null && inner.length() > outer.length() ? inner.substring(outer.length())
        : "";
    List<String> out = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      if (i == start || i == end) {
        continue;
      }
      if (i > start && i < end && !remove.isEmpty() && lines[i].startsWith(outer + remove)) {
        out.add(outer + lines[i].substring(outer.length() + remove.length()));
      } else {
        out.add(lines[i]);
      }
    }
    return String.join("\n", out);
  }

  /** The 0-based line of the closing brace of the loop at {@code line}, or -1. */
  private static int foreverEnd(String[] lines, long line) {
    int start = (int) line - 1;
    if (start < 0 || start >= lines.length
        || !FOREVER_HEADER.matcher(strip(lines[start])).matches()) {
      return -1;
    }
    int depth = 0;
    boolean inBlockComment = false;
    for (int i = start; i < lines.length; i++) {
      String text = strip(lines[i]);
      for (int c = 0; c < text.length(); c++) {
        char ch = text.charAt(c);
        if (inBlockComment) {
          if (ch == '*' && c + 1 < text.length() && text.charAt(c + 1) == '/') {
            inBlockComment = false;
            c++;
          }
          continue;
        }
        if (ch == '/' && c + 1 < text.length() && text.charAt(c + 1) == '/') {
          break;
        }
        if (ch == '/' && c + 1 < text.length() && text.charAt(c + 1) == '*') {
          inBlockComment = true;
          c++;
          continue;
        }
        if (ch == '"' || ch == '\'') {
          c = skipLiteral(text, c);
          continue;
        }
        if (ch == '{') {
          depth++;
        } else if (ch == '}') {
          depth--;
          if (depth == 0) {
            return i > start && CLOSING.matcher(text).matches() ? i : -1;
          }
        }
      }
    }
    return -1;
  }

  private static int skipLiteral(String text, int from) {
    char quote = text.charAt(from);
    for (int c = from + 1; c < text.length(); c++) {
      if (text.charAt(c) == '\\') {
        c++;
      } else if (text.charAt(c) == quote) {
        return c;
      }
    }
    return text.length();
  }

  private static String strip(String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  private static String leadingSpace(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return line.substring(0, i);
  }
}
