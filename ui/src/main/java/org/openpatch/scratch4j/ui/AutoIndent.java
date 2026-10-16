package org.openpatch.scratch4j.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Where a new line and a closing bracket go, worked out from the brackets
 * around the caret (as IntelliJ does), not copied from the line above: a
 * line that is off does not pass its indentation on, and a "}" lands under
 * the line that opened its block. Strings, characters and comments do not
 * count as brackets.
 */
final class AutoIndent {

  private AutoIndent() {}

  /** The indentation for a new line that starts at {@code offset}. */
  static String forNewLine(String text, int offset, String unit) {
    int open = innermostOpen(text, offset);
    if (open < 0) {
      return "";
    }
    String base = indentOfLineAt(text, open);
    // inside "( ... )" or "[ ... ]" a broken line continues the expression: two steps
    return text.charAt(open) == '{' ? base + unit : base + unit + unit;
  }

  /** The indentation for a line that starts with a closing bracket, at {@code lineStart}. */
  static String forCloser(String text, int lineStart) {
    int open = innermostOpen(text, lineStart);
    return open < 0 ? "" : indentOfLineAt(text, open);
  }

  /** Whether {@code c} closes a bracket. */
  static boolean isCloser(char c) {
    return c == '}' || c == ')' || c == ']';
  }

  /** The offset of the innermost bracket still open at {@code offset}, or -1. */
  static int innermostOpen(String text, int offset) {
    Deque<Integer> open = new ArrayDeque<>();
    int end = Math.min(offset, text.length());
    for (int i = 0; i < end; i++) {
      char c = text.charAt(i);
      if (c == '/' && i + 1 < end && text.charAt(i + 1) == '/') {
        i = text.indexOf('\n', i);
        if (i < 0) break;
      } else if (c == '/' && i + 1 < end && text.charAt(i + 1) == '*') {
        int close = text.indexOf("*/", i + 2);
        if (close < 0) break;
        i = close + 1;
      } else if (c == '"' || c == '\'') {
        i = literalEnd(text, i, end);
      } else if (c == '{' || c == '(' || c == '[') {
        open.push(i);
      } else if (isCloser(c) && !open.isEmpty()) {
        open.pop();
      }
    }
    return open.isEmpty() ? -1 : open.peek();
  }

  /** The last offset of the string or char literal starting at {@code start}. */
  private static int literalEnd(String text, int start, int end) {
    char quote = text.charAt(start);
    if (quote == '"' && text.startsWith("\"\"\"", start)) {
      int close = text.indexOf("\"\"\"", start + 3);
      return close < 0 ? end : close + 2;
    }
    for (int i = start + 1; i < end; i++) {
      char c = text.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == quote || c == '\n') {
        // an unclosed literal ends with its line
        return i;
      }
    }
    return end;
  }

  private static String indentOfLineAt(String text, int offset) {
    int lineStart = text.lastIndexOf('\n', offset - 1) + 1;
    int i = lineStart;
    while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
    return text.substring(lineStart, i);
  }
}
