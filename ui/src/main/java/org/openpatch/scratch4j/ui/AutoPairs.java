package org.openpatch.scratch4j.ui;

/**
 * Bracket completion: a typed {@code (}, {@code [}, {@code {}, {@code "} or
 * {@code '} brings its partner along (or wraps the selection), a typed
 * closer steps over the one already there, and Backspace on an empty pair
 * removes both halves. Only in code: inside strings, characters and
 * comments the keys type as usual.
 */
final class AutoPairs {

  /** Replace {@code [start, end)} with {@code text}, then select {@code anchor..caret}. */
  record Edit(int start, int end, String text, int anchor, int caret) {}

  private enum Context { CODE, STRING, CHAR, COMMENT }

  private AutoPairs() {}

  /** What typing {@code c} over the selection {@code [from, to)} does, or null: type it. */
  static Edit typed(String text, int from, int to, char c) {
    char partner = partner(c);
    if (from != to) {
      if (partner == 0 || context(text, from) != Context.CODE) return null;
      // the selection stays selected, now inside the pair
      return new Edit(from, to, c + text.substring(from, to) + partner, from + 1, to + 1);
    }
    int caret = from;
    char next = caret < text.length() ? text.charAt(caret) : 0;
    char before = caret > 0 ? text.charAt(caret - 1) : 0;
    Context context = context(text, caret);
    if (AutoIndent.isCloser(c)) {
      return context == Context.CODE && next == c && balancedLine(text, caret, opener(c), c)
          ? step(caret) : null;
    }
    if (c == '"' || c == '\'') {
      Context literal = c == '"' ? Context.STRING : Context.CHAR;
      if (context == literal) {
        // the closing quote that is already there
        return next == c && before != '\\' ? step(caret) : null;
      }
      if (context != Context.CODE || Character.isJavaIdentifierPart(before) || before == c
          || !endsExpression(next)) {
        return null;
      }
      return new Edit(caret, caret, "" + c + c, caret + 1, caret + 1);
    }
    if (partner != 0 && context == Context.CODE && endsExpression(next)) {
      return new Edit(caret, caret, "" + c + partner, caret + 1, caret + 1);
    }
    return null;
  }

  /** What Backspace at {@code caret} (no selection) does, or null: delete one character. */
  static Edit backspace(String text, int caret) {
    if (caret <= 0 || caret >= text.length()) return null;
    char open = text.charAt(caret - 1);
    char partner = partner(open);
    if (partner == 0 || text.charAt(caret) != partner
        || context(text, caret - 1) != Context.CODE) {
      return null;
    }
    return new Edit(caret - 1, caret + 1, "", caret - 1, caret - 1);
  }

  private static Edit step(int caret) {
    return new Edit(caret, caret, "", caret + 1, caret + 1);
  }

  private static char partner(char open) {
    return switch (open) {
      case '(' -> ')';
      case '[' -> ']';
      case '{' -> '}';
      case '"' -> '"';
      case '\'' -> '\'';
      default -> 0;
    };
  }

  private static char opener(char close) {
    return switch (close) {
      case ')' -> '(';
      case ']' -> '[';
      default -> '{';
    };
  }

  /** A pair goes in only where nothing follows that it would swallow: "(|x" stays. */
  private static boolean endsExpression(char next) {
    return next == 0 || Character.isWhitespace(next) || AutoIndent.isCloser(next)
        || next == ';' || next == ',';
  }

  /**
   * Whether the caret's line closes every {@code open} it opens: then a typed
   * closer in front of the same one steps over it. On {@code f(g(x|)} it
   * goes in, since the line still misses one.
   */
  private static boolean balancedLine(String text, int caret, char open, char close) {
    int start = text.lastIndexOf('\n', caret - 1) + 1;
    int end = text.indexOf('\n', caret);
    if (end < 0) end = text.length();
    int depth = 0;
    for (int i = start; i < end; i++) {
      char c = text.charAt(i);
      if (c == '/' && i + 1 < end && text.charAt(i + 1) == '/') break;
      if (c == '"' || c == '\'') {
        for (i++; i < end && text.charAt(i) != c; i++) {
          if (text.charAt(i) == '\\') i++;
        }
      } else if (c == open) {
        depth++;
      } else if (c == close) {
        depth--;
      }
    }
    return depth <= 0;
  }

  /** Whether {@code offset} lies in code, a string, a char literal or a comment. */
  private static Context context(String text, int offset) {
    int i = 0;
    while (i < offset) {
      char c = text.charAt(i);
      char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
      if (c == '/' && next == '/') {
        int end = text.indexOf('\n', i);
        if (end < 0 || end >= offset) return Context.COMMENT;
        i = end + 1;
      } else if (c == '/' && next == '*') {
        int end = text.indexOf("*/", i + 2);
        if (end < 0 || end + 2 > offset) return Context.COMMENT;
        i = end + 2;
      } else if (text.startsWith("\"\"\"", i)) {
        int end = text.indexOf("\"\"\"", i + 3);
        if (end < 0 || end + 3 > offset) return Context.STRING;
        i = end + 3;
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < text.length() && text.charAt(j) != c && text.charAt(j) != '\n') {
          j += text.charAt(j) == '\\' ? 2 : 1;
        }
        // up to its closing quote (an unclosed literal ends with its line)
        if (j >= offset) return c == '"' ? Context.STRING : Context.CHAR;
        i = j < text.length() && text.charAt(j) == c ? j + 1 : j;
      } else {
        i++;
      }
    }
    return Context.CODE;
  }
}
