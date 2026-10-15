package org.openpatch.scratch4j.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a block dragged from the palette may land in Java code, and what it
 * puts there. A stack block (a call ending in {@code ;}) takes a line of its
 * own inside a method or constructor body, between two statements; a hat
 * block (an event method) takes lines of its own in a class body, between two
 * members; a reporter (a value) goes inline, inside a body. The code is read
 * like {@link MemberPlacement} reads it: braces, parentheses, comments and
 * literals, no parser.
 */
final class BlockDrop {

  private BlockDrop() { }

  enum Kind { STATEMENT, MEMBER, EXPRESSION }

  /** Why a block can't go somewhere. */
  enum Problem {
    /** a call outside any method or constructor body */
    BODY,
    /** in a body, but in the middle of a statement */
    STATEMENT,
    /** an event method outside a class body (in a method, before the class) */
    CLASS_BODY,
    /** in a class body, but in the middle of a member (an annotation, a field's value) */
    MEMBER,
    /** the class already has this event method */
    EXISTS,
    /** in a comment, a string or a name */
    TEXT
  }

  /**
   * The lines a block goes on: new lines before {@code line} (the line count:
   * after the last line), or blank {@code line} itself when {@code fill}.
   */
  record Slot(int line, boolean fill) { }

  /**
   * What a drop does: {@code text} replaces {@code start..end} and the caret
   * goes to {@code caret}. The {@code code} lines (at {@code indent}) follow
   * {@code blankBefore} blank lines; {@code addedRows} lines are new.
   */
  record Insertion(int start, int end, String text, int caret, String indent,
      List<String> code, int blankBefore, int addedRows) { }

  /**
   * A drop: where it goes and what it inserts, or why it can't go there
   * ({@code existingLine}, 1-based, for {@link Problem#EXISTS}). A reporter
   * has no slot.
   */
  record Target(Kind kind, Slot slot, Insertion insertion, Problem problem, int existingLine) {
    boolean valid() {
      return problem == null;
    }
  }

  static final String INDENT = "  ";

  /** An event block's snippet: a method; group 1 is its name. */
  static final Pattern EVENT_METHOD = Pattern.compile(
      "^(?:public |protected |private )?void (\\w+)\\([^)]*\\) \\{\n");

  private static final Pattern TYPE = Pattern.compile(
      "(^|[^\\w$.])(class|interface|enum|record)\\s+[A-Za-z_$]");
  private static final Pattern ENUM = Pattern.compile("(^|[^\\w$.])enum\\s+[A-Za-z_$]");
  private static final Pattern NEW_TYPE = Pattern.compile(
      "(^|[^\\w$])new\\s+[\\w$.]+\\s*(<[^()]*>)?$");
  private static final Pattern SWITCH = Pattern.compile("(^|[^\\w$])switch$");
  private static final Pattern CASE = Pattern.compile("case\\b.*|default");
  private static final Pattern LABEL = Pattern.compile("[A-Za-z_$][\\w$]*");
  private static final Pattern VALUE_START = Pattern.compile("(return|yield|throw)\\b.*");
  private static final List<String> CONTINUATIONS = List.of("else", "catch", "finally");

  static Kind kindOf(String snippet) {
    if (!snippet.endsWith("\n")) return Kind.EXPRESSION;
    return eventMethod(snippet) != null ? Kind.MEMBER : Kind.STATEMENT;
  }

  /** The name of the method an event block's snippet declares, else null. */
  static String eventMethod(String snippet) {
    Matcher m = EVENT_METHOD.matcher(snippet);
    return m.find() ? m.group(1) : null;
  }

  /**
   * The drop of a stack or hat block over {@code line}: on the line when it is
   * blank, else on a new line above it ({@code upper} half) or below it. When
   * that side can't take the block, the other one is tried.
   */
  static Target onLine(String text, int line, boolean upper, String snippet) {
    int[] starts = lineStarts(text);
    int at = Math.max(0, Math.min(line, starts.length - 1));
    List<Slot> slots;
    if (lineText(text, starts, at).isBlank()) {
      slots = List.of(new Slot(at, true));
    } else if (upper) {
      slots = List.of(new Slot(at, false), new Slot(at + 1, false));
    } else {
      slots = List.of(new Slot(at + 1, false), new Slot(at, false));
    }
    Target first = null;
    for (Slot slot : slots) {
      Target target = at(text, slot, snippet);
      if (target.valid()) return target;
      if (first == null) first = target;
    }
    return first;
  }

  /** A stack or hat block at {@code slot}. */
  static Target at(String text, Slot slot, String snippet) {
    Kind kind = kindOf(snippet);
    int[] starts = lineStarts(text);
    int offset = slot.line() < starts.length ? starts[slot.line()] : text.length();
    Scan scan = scan(text, offset);
    Problem problem = kind == Kind.MEMBER ? memberProblem(scan)
        : statementProblem(scan, text, offset);
    int existing = -1;
    if (problem == null && kind == Kind.MEMBER) {
      var place = MemberPlacement.place(text, offset, eventMethod(snippet));
      if (place != null && place.existing()) {
        problem = Problem.EXISTS;
        existing = lineAt(starts, place.offset()) + 1;
      }
    }
    return new Target(kind, slot, problem == null ? insertion(text, slot, snippet) : null,
        problem, existing);
  }

  /** A reporter dropped at {@code offset}: it goes right there, inside a body. */
  static Target inline(String text, int offset, String snippet) {
    Scan scan = scan(text, offset);
    Problem problem = null;
    if (scan.inText() || inWord(text, offset)) {
      problem = Problem.TEXT;
    } else if (!scan.inBody()) {
      problem = Problem.BODY;
    }
    Insertion insertion = problem != null ? null : new Insertion(offset, offset, snippet,
        offset + snippet.length(), "", List.of(snippet), 0, 0);
    return new Target(Kind.EXPRESSION, null, insertion, problem, -1);
  }

  /** The text that puts a stack or hat block at {@code slot}, indented like the code around. */
  static Insertion insertion(String text, Slot slot, String snippet) {
    int[] starts = lineStarts(text);
    String body = snippet.endsWith("\n") ? snippet.substring(0, snippet.length() - 1) : snippet;
    // the snippets indent by two spaces, the file may use another step
    String unit = indentUnit(text, starts);
    List<String> code = body.lines().map(line -> reindent(line, unit)).toList();
    boolean method = kindOf(snippet) == Kind.MEMBER;
    int above = slot.line() - 1;
    int below = slot.fill() ? slot.line() + 1 : slot.line();
    String indent = indentFor(text, starts, above, below, unit);
    // a method keeps a blank line to the members beside it
    String previous = above >= 0 ? lineText(text, starts, above) : "";
    String following = below < starts.length ? lineText(text, starts, below) : "";
    boolean blankBefore = method && !previous.isBlank() && !previous.strip().endsWith("{");
    boolean blankAfter = method && !following.isBlank() && !following.strip().startsWith("}");

    StringBuilder block = new StringBuilder();
    if (blankBefore) block.append('\n');
    int caret = -1;
    for (int i = 0; i < code.size(); i++) {
      if (i > 0) block.append('\n');
      block.append(indent).append(code.get(i));
      // an empty method body: the caret goes inside it
      if (caret < 0 && i > 0 && code.get(i).isBlank() && code.get(i - 1).strip().endsWith("{")) {
        caret = block.length();
      }
    }
    if (caret < 0) caret = block.length();
    if (blankAfter) block.append('\n');
    int rows = code.size() + (blankBefore ? 1 : 0) + (blankAfter ? 1 : 0);

    if (slot.fill()) {
      int start = starts[slot.line()];
      int end = start + lineText(text, starts, slot.line()).length();
      return new Insertion(start, end, block.toString(), start + caret, indent, code,
          blankBefore ? 1 : 0, rows - 1);
    }
    if (slot.line() < starts.length) {
      int start = starts[slot.line()];
      return new Insertion(start, start, block + "\n", start + caret, indent, code,
          blankBefore ? 1 : 0, rows);
    }
    int end = text.length();
    return new Insertion(end, end, "\n" + block, end + 1 + caret, indent, code,
        blankBefore ? 1 : 0, rows);
  }

  // --- indentation ---------------------------------------------------------------

  /** The indent of new lines between line {@code above} and line {@code below}. */
  private static String indentFor(String text, int[] starts, int above, int below,
      String unit) {
    String previous = null;
    for (int i = Math.min(above, starts.length - 1); i >= 0 && previous == null; i--) {
      if (!lineText(text, starts, i).isBlank()) previous = lineText(text, starts, i);
    }
    String next = null;
    for (int i = Math.max(below, 0); i < starts.length && next == null; i++) {
      if (!lineText(text, starts, i).isBlank()) next = lineText(text, starts, i);
    }
    if (next != null && !next.strip().startsWith("}")) return indentOf(next);
    if (previous != null && previous.strip().endsWith("{")) return indentOf(previous) + unit;
    if (next != null) return indentOf(next) + unit;
    return previous == null ? "" : indentOf(previous);
  }

  /** One indent step of the file (from a line opening a brace to the next), else two spaces. */
  private static String indentUnit(String text, int[] starts) {
    String opener = null;
    int smallest = Integer.MAX_VALUE;
    for (int i = 0; i < starts.length; i++) {
      String line = lineText(text, starts, i);
      if (line.isBlank()) continue;
      if (opener != null) {
        String inner = indentOf(line);
        String outer = indentOf(opener);
        if (inner.length() > outer.length() && inner.startsWith(outer)) {
          String step = inner.substring(outer.length());
          if (step.contains("\t")) return "\t";
          smallest = Math.min(smallest, step.length());
        }
      }
      opener = line.strip().endsWith("{") ? line : null;
    }
    return smallest == Integer.MAX_VALUE || smallest > 8 ? INDENT : " ".repeat(smallest);
  }

  private static String reindent(String line, String unit) {
    StringBuilder lead = new StringBuilder();
    int i = 0;
    while (line.startsWith(INDENT, i)) {
      lead.append(unit);
      i += INDENT.length();
    }
    return lead + line.substring(i);
  }

  private static String indentOf(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
    return line.substring(0, i);
  }

  // --- reading the code ------------------------------------------------------------

  private enum Body { TOP, TYPE, CODE, SWITCH, ARRAY }

  /** What one pair of braces holds, read up to the offset. */
  private static final class Frame {
    final Body body;
    /** closing it ends a statement or member of the body around (else an expression goes on) */
    final boolean statement;
    /** {@code do { }}: {@code while (...);} still follows */
    final boolean isDo;
    final Frame parent;
    /** open ( and [ */
    int parens;
    /** an enum's constants, before the first {@code ;} */
    boolean enumConstants;
    /** a switch body after a {@code case ...:} label (not {@code case ... ->}) */
    boolean colonCase;
    /** the statement (or member) so far, literals blanked; empty between two */
    final StringBuilder stmt = new StringBuilder();

    Frame(Body body, boolean statement, boolean isDo, Frame parent) {
      this.body = body;
      this.statement = statement;
      this.isDo = isDo;
      this.parent = parent;
    }
  }

  /** The code around an offset; {@code inText}: in a comment or a literal. */
  private record Scan(Frame frame, boolean inText) {

    boolean atBoundary() {
      return frame.parens == 0 && frame.stmt.isEmpty();
    }

    /** In a method, constructor, lambda or initializer body (array values inside one count). */
    boolean inBody() {
      for (Frame f = frame; f != null; f = f.parent) {
        if (f.body == Body.CODE || f.body == Body.SWITCH) return true;
        if (f.body != Body.ARRAY) return false;
      }
      return false;
    }
  }

  private static Problem statementProblem(Scan scan, String text, int offset) {
    if (scan.inText()) return Problem.TEXT;
    Frame frame = scan.frame();
    boolean statements = frame.body == Body.CODE
        || frame.body == Body.SWITCH && frame.colonCase;
    if (!statements) return scan.inBody() ? Problem.STATEMENT : Problem.BODY;
    if (!scan.atBoundary() || CONTINUATIONS.contains(nextWord(text, offset))) {
      return Problem.STATEMENT;
    }
    return null;
  }

  private static Problem memberProblem(Scan scan) {
    if (scan.inText()) return Problem.TEXT;
    Frame frame = scan.frame();
    if (frame.body != Body.TYPE) return Problem.CLASS_BODY;
    if (!scan.atBoundary() || frame.enumConstants) return Problem.MEMBER;
    return null;
  }

  private static Scan scan(String text, int offset) {
    Frame frame = new Frame(Body.TOP, false, false, null);
    int i = 0;
    while (i < offset) {
      char c = text.charAt(i);
      char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
      if (Character.isWhitespace(c)) {
        space(frame);
        i++;
        continue;
      }
      if (c == '/' && next == '/') {
        int end = text.indexOf('\n', i);
        if (end < 0 || end >= offset) return new Scan(frame, true);
        i = end;
        continue;
      }
      if (c == '/' && next == '*') {
        int end = text.indexOf("*/", i + 2);
        if (end < 0 || end + 2 > offset) return new Scan(frame, true);
        space(frame);
        i = end + 2;
        continue;
      }
      if (c == '"' || c == '\'') {
        int end = skipLiteral(text, i);
        if (end > offset) return new Scan(frame, true);
        frame.stmt.append("\"\"");
        i = end;
        continue;
      }
      if (c == ':' && next == ':') {
        frame.stmt.append("::");
        i += 2;
        continue;
      }
      if (c == '-' && next == '>') {
        if (frame.parens == 0 && frame.body == Body.SWITCH && isCase(frame.stmt)) {
          frame.colonCase = false;
        }
        frame.stmt.append("->");
        i += 2;
        continue;
      }
      switch (c) {
        case '(', '[' -> {
          frame.parens++;
          frame.stmt.append(c);
        }
        case ')', ']' -> {
          if (frame.parens > 0) frame.parens--;
          frame.stmt.append(c);
        }
        case '{' -> frame = open(frame);
        case '}' -> frame = close(frame);
        case ';' -> {
          if (frame.parens == 0) {
            frame.stmt.setLength(0);
            frame.enumConstants = false;
          } else {
            frame.stmt.append(c);
          }
        }
        case ':' -> {
          if (frame.parens == 0 && isLabel(frame.stmt)) {
            if (frame.body == Body.SWITCH) frame.colonCase = true;
            frame.stmt.setLength(0);
          } else {
            frame.stmt.append(c);
          }
        }
        default -> frame.stmt.append(c);
      }
      i++;
    }
    return new Scan(frame, false);
  }

  private static void space(Frame frame) {
    StringBuilder stmt = frame.stmt;
    if (!stmt.isEmpty() && stmt.charAt(stmt.length() - 1) != ' ') stmt.append(' ');
  }

  /** A brace opens: what it holds follows from the code before it. */
  private static Frame open(Frame outer) {
    String header = outer.stmt.toString().trim();
    Body body;
    boolean statement = false;
    boolean isDo = false;
    boolean isEnum = false;
    if (header.endsWith("->")) {
      body = Body.CODE; // a lambda's body, a `case X -> {` block
    } else if (anonymousClass(header)) {
      body = Body.TYPE;
    } else if (outer.parens > 0) {
      body = Body.ARRAY; // values in an argument (an annotation's array)
    } else if (TYPE.matcher(header).find()) {
      body = Body.TYPE;
      statement = true;
      isEnum = ENUM.matcher(header).find();
    } else if (outer.body == Body.ARRAY || header.endsWith("=") || header.endsWith(",")
        || header.endsWith("]")) {
      body = Body.ARRAY;
    } else {
      body = SWITCH.matcher(beforeParentheses(header)).find() ? Body.SWITCH : Body.CODE;
      statement = !value(header);
      isDo = header.equals("do");
    }
    outer.stmt.append('{');
    Frame inner = new Frame(body, statement, isDo, outer);
    inner.enumConstants = isEnum;
    return inner;
  }

  private static Frame close(Frame inner) {
    Frame outer = inner.parent;
    if (outer == null) return inner; // one } too many
    if (inner.statement && outer.parens == 0 && !inner.isDo) {
      outer.stmt.setLength(0);
    } else {
      outer.stmt.append('}');
    }
    return outer;
  }

  /** {@code new Type(...)} right before a brace: an anonymous class. */
  private static boolean anonymousClass(String header) {
    if (!header.endsWith(")")) return false;
    return NEW_TYPE.matcher(beforeParentheses(header)).find();
  }

  /** The header before its last (...) group, trimmed; the header itself without one. */
  private static String beforeParentheses(String header) {
    if (!header.endsWith(")")) return header;
    int depth = 0;
    for (int i = header.length() - 1; i >= 0; i--) {
      char c = header.charAt(i);
      if (c == ')') depth++;
      if (c == '(' && --depth == 0) return header.substring(0, i).trim();
    }
    return header;
  }

  /** The header is part of a value ({@code x = switch (y) {}, return switch ...}). */
  private static boolean value(String header) {
    if (VALUE_START.matcher(header).matches()) return true;
    int depth = 0;
    for (int i = 0; i < header.length(); i++) {
      char c = header.charAt(i);
      if (c == '(' || c == '[') depth++;
      if (c == ')' || c == ']') depth--;
      if (c == '=' && depth == 0) {
        char before = i > 0 ? header.charAt(i - 1) : ' ';
        char after = i + 1 < header.length() ? header.charAt(i + 1) : ' ';
        if (after != '=' && before != '=' && before != '!' && before != '<' && before != '>') {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isLabel(StringBuilder stmt) {
    String s = stmt.toString().trim();
    if (s.contains("?") || s.contains("->")) return false;
    return CASE.matcher(s).matches() || LABEL.matcher(s).matches();
  }

  private static boolean isCase(StringBuilder stmt) {
    return CASE.matcher(stmt.toString().trim()).matches();
  }

  /** The word after {@code offset}, past spaces and comments ("" for none). */
  private static String nextWord(String text, int offset) {
    int i = offset;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (text.startsWith("//", i)) {
        int end = text.indexOf('\n', i);
        i = end < 0 ? text.length() : end;
      } else if (text.startsWith("/*", i)) {
        int end = text.indexOf("*/", i + 2);
        i = end < 0 ? text.length() : end + 2;
      } else {
        break;
      }
    }
    int start = i;
    while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) i++;
    return text.substring(start, i);
  }

  private static boolean inWord(String text, int offset) {
    return offset > 0 && offset < text.length()
        && Character.isJavaIdentifierPart(text.charAt(offset - 1))
        && Character.isJavaIdentifierPart(text.charAt(offset));
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

  // --- lines -----------------------------------------------------------------------

  static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') starts.add(i + 1);
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  private static int lineAt(int[] starts, int offset) {
    int found = java.util.Arrays.binarySearch(starts, offset);
    return found >= 0 ? found : -found - 2;
  }

  private static String lineText(String text, int[] starts, int line) {
    int start = starts[line];
    int end = line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
    return text.substring(start, end);
  }
}
