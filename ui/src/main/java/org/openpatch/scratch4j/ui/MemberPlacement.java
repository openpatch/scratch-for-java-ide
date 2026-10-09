package org.openpatch.scratch4j.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Where a method from the block palette (an event block, {@code when ...})
 * belongs: in a class body, never inside another method. Braces are counted
 * past comments, strings and char literals; a brace opens a class when the
 * code before it names one ({@code class}, {@code interface}, {@code enum},
 * {@code record}); anonymous classes and lambdas count as code.
 */
final class MemberPlacement {

  private MemberPlacement() { }

  /**
   * Where to put the method: after the line of {@code offset}, or, when
   * {@code existing}, the caret inside the method of that name the class
   * already has.
   */
  record Place(int offset, boolean existing) { }

  private static final Pattern TYPE = Pattern.compile(
      "(^|[^\\w@])(class|interface|enum|record)\\s+\\w+");

  private static final class Brace {
    final int open;
    final boolean type;
    final String header;
    final Brace parent;
    int close = -1;

    Brace(int open, boolean type, String header, Brace parent) {
      this.open = open;
      this.type = type;
      this.header = header;
      this.parent = parent;
    }
  }

  /**
   * The place for method {@code name} when the block would be inserted after
   * the line ending at {@code lineEnd}; null when that line already is in a
   * class body (or the file has no class to put it in).
   */
  static Place place(String text, int lineEnd, String name) {
    List<Brace> braces = new ArrayList<>();
    List<Brace> atLine = null;
    List<Brace> stack = new ArrayList<>();
    StringBuilder header = new StringBuilder();
    int i = 0;
    while (i <= text.length()) {
      if (i >= lineEnd && atLine == null) {
        atLine = List.copyOf(stack);
      }
      if (i == text.length()) break;
      char c = text.charAt(i);
      char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
      if (c == '/' && next == '/') {
        int end = text.indexOf('\n', i);
        i = end < 0 ? text.length() : end;
        continue;
      }
      if (c == '/' && next == '*') {
        int end = text.indexOf("*/", i + 2);
        i = end < 0 ? text.length() : end + 2;
        header.append(' ');
        continue;
      }
      if (c == '"' || c == '\'') {
        i = skipLiteral(text, i);
        header.append(" \"\" ");
        continue;
      }
      if (c == '{') {
        Brace parent = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        String head = header.toString();
        Brace brace = new Brace(i, TYPE.matcher(head).find(), head, parent);
        braces.add(brace);
        stack.add(brace);
        header.setLength(0);
      } else if (c == '}') {
        if (!stack.isEmpty()) stack.remove(stack.size() - 1).close = i;
        header.setLength(0);
      } else if (c == ';') {
        header.setLength(0);
      } else {
        header.append(c);
      }
      i++;
    }
    if (atLine == null) atLine = List.copyOf(stack);

    // the innermost class around the line, else the file's first class
    int typeIndex = -1;
    for (int k = atLine.size() - 1; k >= 0; k--) {
      if (atLine.get(k).type) {
        typeIndex = k;
        break;
      }
    }
    Brace type = typeIndex >= 0 ? atLine.get(typeIndex)
        : braces.stream().filter(b -> b.type && b.parent == null).findFirst().orElse(null);
    if (type == null) return null;

    Pattern same = Pattern.compile("\\bvoid\\s+" + Pattern.quote(name) + "\\s*\\(");
    for (Brace member : braces) {
      if (member.parent == type && !member.type && same.matcher(member.header).find()) {
        return new Place(member.open + 1, true);
      }
    }
    if (typeIndex >= 0 && typeIndex == atLine.size() - 1) {
      return null; // already in the class body
    }
    if (typeIndex >= 0) {
      // inside a method (or a block of the class): after the member's closing brace
      Brace member = atLine.get(typeIndex + 1);
      return member.close < 0 ? null : new Place(member.close, false);
    }
    // outside the class: before its closing brace
    if (type.close < 0) return null;
    int lineBefore = text.lastIndexOf('\n', type.close - 1);
    return lineBefore <= type.open ? null : new Place(lineBefore, false);
  }

  /** The index after the string, text block or char literal starting at {@code start}. */
  private static int skipLiteral(String text, int start) {
    char quote = text.charAt(start);
    if (quote == '"' && text.startsWith("\"\"\"", start)) {
      int end = text.indexOf("\"\"\"", start + 3);
      return end < 0 ? text.length() : end + 3;
    }
    int i = start + 1;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote || c == '\n') return i + 1;
      i++;
    }
    return text.length();
  }
}
