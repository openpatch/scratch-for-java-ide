package org.openpatch.scratch4j.core.lint;

import java.util.ArrayDeque;

/** Bracket pairs outside Java literals and comments; retains the opening line. */
final class Brackets {
  record Opening(char token, int line) {}
  record Mismatch(String open, int openLine, String expected, String actual, int line) {}
  static Mismatch mismatch(String source) {
    var stack = new ArrayDeque<Opening>();
    int line = 1;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\n') { line++; continue; }
      if (source.startsWith("//", i)) {
        while (i < source.length() && source.charAt(i) != '\n') i++;
        if (i < source.length()) line++;
        continue;
      }
      if (source.startsWith("/*", i)) {
        i += 2;
        while (i < source.length() && !source.startsWith("*/", i)) {
          if (source.charAt(i++) == '\n') line++;
        }
        i++;
        continue;
      }
      if (c == '"' || c == '\'') {
        boolean block = source.startsWith("\"\"\"", i);
        i += block ? 3 : 1;
        while (i < source.length()) {
          if (source.charAt(i) == '\n') line++;
          if (source.charAt(i) == '\\') { i += 2; continue; }
          if (block ? source.startsWith("\"\"\"", i) : source.charAt(i) == c) break;
          i++;
        }
        if (block) i += 2;
        continue;
      }
      if ("([{".indexOf(c) >= 0) stack.push(new Opening(c, line));
      int close = ")]}".indexOf(c);
      if (close >= 0 && !stack.isEmpty()) {
        Opening open = stack.pop();
        char expected = ")]}".charAt("([{".indexOf(open.token()));
        if (expected != c) return new Mismatch("" + open.token(), open.line(),
            "" + expected, "" + c, line);
      }
    }
    return null;
  }
}
