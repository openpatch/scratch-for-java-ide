package org.openpatch.scratch4j.core.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Formats Java the way the library's docs write it (2-space indent, spaces
 * around operators and after commas, {@code if (}, {@code ) {}, {@code } else})
 * while keeping the student's line breaks. It only ever changes whitespace:
 * strings, text blocks, characters and comments stay byte for byte, so the
 * formatted program is the same program.
 */
public final class JavaFormatter {

  private static final String INDENT = "  ";
  private static final Set<String> SPACED_KEYWORDS = Set.of("if", "for", "while", "switch",
      "catch", "synchronized", "try", "return", "else", "do", "finally", "new", "throw",
      "case", "assert", "instanceof");
  /** Binary operators that get a space on both sides. */
  private static final Set<String> BINARY = Set.of("=", "==", "!=", "<=", ">=", "&&", "||",
      "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "->", "?", "*", "/",
      "%", "+", "-", "&", "|", "^", "<<");
  private static final Set<String> PRIMITIVES = Set.of("int", "double", "float", "long",
      "short", "byte", "char", "boolean");
  private static final String[] OPERATORS = {">>>=", "<<=", ">>=", "...", "->", "::", "==", "!=",
      "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<",
      "=", "+", "-", "*", "/", "%", "&", "|", "^", "!", "~", "?", ":", "<", ">", "(", ")", "[",
      "]", "{", "}", ";", ",", ".", "@"};

  /** RAW_TEXT/RAW_COMMENT: further lines of a text block / block comment, kept as written. */
  private enum Kind { WORD, NUMBER, LITERAL, COMMENT, OPERATOR, RAW_TEXT, RAW_COMMENT }

  private record Token(Kind kind, String text) {}

  private JavaFormatter() {}

  public static String format(String source) {
    List<String> lines = new ArrayList<>();
    // the lexer works on the whole text (text blocks and block comments span lines)
    List<List<Token>> tokenLines = lex(source);
    int depth = 0;
    // indent levels each open bracket adds: { = 1, ( [ and array initializers = 2
    List<Integer> opened = new ArrayList<>();
    boolean continuation = false;
    boolean previousEndsWithOperand = false;
    List<Integer> switchStack = new ArrayList<>();
    // blocks opened on a case label ("case 1: {") indent like the label's body
    List<Integer> caseBlocks = new ArrayList<>();
    int blankRun = 0;
    for (List<Token> tokens : tokenLines) {
      if (tokens.isEmpty()) {
        if (++blankRun <= 1 && !lines.isEmpty()) {
          lines.add("");
        }
        continue;
      }
      blankRun = 0;
      Token first = tokens.get(0);
      int lineDepth = depth;
      int levels = sum(opened);
      if ((first.text().equals("}") || first.text().equals(")") || first.text().equals("]"))
          && !opened.isEmpty()) {
        lineDepth--;
        levels -= opened.get(opened.size() - 1);
      }
      boolean caseLabel = first.kind() == Kind.WORD
          && (first.text().equals("case") || first.text().equals("default"))
          && !switchStack.isEmpty() && switchStack.get(switchStack.size() - 1) == depth;
      // every enclosing switch indents its case bodies one more level
      int switchExtra = 0;
      for (int open : switchStack) {
        if (lineDepth >= open) {
          switchExtra++;
        }
      }
      if (caseLabel) {
        switchExtra--;
      }
      for (int block : caseBlocks) {
        if (lineDepth >= block || first.text().equals("}") && lineDepth + 1 == block) {
          switchExtra--;
        }
      }
      boolean closing = first.text().equals("}") || first.text().equals(")")
          || first.text().equals("]");
      int indent = Math.max(0, levels) + (continuation && !closing ? 2 : 0)
          + Math.max(0, switchExtra);
      if (first.kind() == Kind.RAW_TEXT || first.kind() == Kind.RAW_COMMENT) {
        // a line that continues a text block (exactly as written) or a block
        // comment (re-indented " * ..."), then whatever code follows its end
        String raw = first.kind() == Kind.RAW_TEXT ? first.text()
            : INDENT.repeat(Math.max(0, depth)) + " " + first.text().stripLeading();
        List<Token> rest = tokens.subList(1, tokens.size());
        lines.add(rest.isEmpty() ? raw : raw + joinAfter(first, rest));
      } else {
        lines.add(INDENT.repeat(indent) + join(tokens, previousEndsWithOperand));
      }
      // depth and continuation for the next line
      int depthBefore = depth;
      String lastCode = null;
      Token lastCodeToken = null;
      for (int i = 0; i < tokens.size(); i++) {
        Token t = tokens.get(i);
        if (t.kind() == Kind.OPERATOR) {
          switch (t.text()) {
            case "{", "(", "[" -> {
              depth++;
              boolean arrayInit = t.text().equals("{") && i > 0
                  && (tokens.get(i - 1).text().equals("=") || tokens.get(i - 1).text().equals("]"));
              opened.add(t.text().equals("{") && !arrayInit ? 1 : 2);
              if (t.text().equals("{") && i >= 1 && previousWord(tokens, i, "switch")) {
                switchStack.add(depth);
              } else if (t.text().equals("{") && caseLabel) {
                caseBlocks.add(depth);
              }
            }
            case "}", ")", "]" -> {
              if (t.text().equals("}") && !switchStack.isEmpty()
                  && switchStack.get(switchStack.size() - 1) == depth) {
                switchStack.remove(switchStack.size() - 1);
              }
              if (t.text().equals("}") && caseBlocks.contains(depth)) {
                caseBlocks.remove(Integer.valueOf(depth));
              }
              depth = Math.max(0, depth - 1);
              if (!opened.isEmpty()) {
                opened.remove(opened.size() - 1);
              }
            }
            default -> { }
          }
        }
        if (t.kind() != Kind.COMMENT && t.kind() != Kind.RAW_COMMENT) {
          lastCode = t.text();
          lastCodeToken = t;
        }
      }
      previousEndsWithOperand = lastCodeToken != null && (isOperand(lastCodeToken)
          || lastCodeToken.kind() == Kind.RAW_TEXT);
      // a line that leaves a bracket open indents the next by that bracket instead
      continuation = lastCode != null && depth <= depthBefore
          && !lastCode.equals(";") && !lastCode.equals("{") && !lastCode.equals("}")
          && !lastCode.equals(":") && !lastCode.equals(",") && !lastCode.equals("(")
          && !lastCode.startsWith("@") && !(first.text().equals("@") && tokens.size() <= 4)
          && !isDeclarationHead(tokens);
    }
    while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    return String.join("\n", lines) + "\n";
  }

  private static int sum(List<Integer> values) {
    int total = 0;
    for (int v : values) {
      total += v;
    }
    return total;
  }

  /** "public class X extends Y" / annotations / labels: no continuation indent. */
  private static boolean isDeclarationHead(List<Token> tokens) {
    for (Token t : tokens) {
      if (t.kind() == Kind.WORD && (t.text().equals("class") || t.text().equals("interface")
          || t.text().equals("enum") || t.text().equals("record"))) {
        return true;
      }
    }
    return tokens.get(0).text().equals("@");
  }

  private static boolean previousWord(List<Token> tokens, int brace, String word) {
    int parens = 0;
    for (int i = brace - 1; i >= 0; i--) {
      String text = tokens.get(i).text();
      if (text.equals(")")) {
        parens++;
      } else if (text.equals("(")) {
        parens--;
      } else if (parens == 0) {
        return text.equals(word);
      }
    }
    return false;
  }

  /** Code after the end of a text block on the same line: """; or """.formatted(x). */
  private static String joinAfter(Token raw, List<Token> rest) {
    List<Token> all = new ArrayList<>();
    all.add(new Token(Kind.LITERAL, "\"\"\""));
    all.addAll(rest);
    String joined = join(all);
    return joined.substring(3);
  }

  /** Spacing between tokens of one line. */
  private static String join(List<Token> tokens) {
    return join(tokens, false);
  }

  /** {@code continued}: the line wraps an expression, so a leading + or - is binary. */
  private static String join(List<Token> tokens, boolean continued) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (i == 1 && continued && (tokens.get(0).text().equals("+")
          || tokens.get(0).text().equals("-"))) {
        sb.append(' ');
      } else if (i > 0 && space(tokens, i)) {
        sb.append(' ');
      }
      sb.append(t.text());
    }
    return sb.toString();
  }

  /** Whether a space goes between tokens i-1 and i. */
  private static boolean space(List<Token> tokens, int i) {
    Token prev = tokens.get(i - 1);
    Token cur = tokens.get(i);
    String p = prev.text();
    String c = cur.text();
    if (cur.kind() == Kind.COMMENT) {
      return true;
    }
    if (prev.kind() == Kind.COMMENT) {
      return true;
    }
    // >> and >>> are lexed as single '>' tokens (generics close with them):
    // "> >" is never valid Java, a space there would break a shift operator
    if (p.equals(">") && c.equals(">")) {
      return false;
    }
    if (c.equals(",") || c.equals(";") || c.equals(")") || c.equals("]") || c.equals(".")
        || c.equals("::") || c.equals("++") && isOperand(prev) || c.equals("--") && isOperand(prev)
        || c.equals("...")) {
      return false;
    }
    if (p.equals("(") || p.equals("[") || p.equals(".") || p.equals("::") || p.equals("@")
        || p.equals("!") || p.equals("~")) {
      return false;
    }
    if (p.equals(",") || p.equals(";")) {
      return true;
    }
    if (c.equals("(") && p.equals(")") && i >= 3 && tokens.get(i - 2).kind() == Kind.WORD
        && tokens.get(i - 3).text().equals("(") && (Character.isUpperCase(
            tokens.get(i - 2).text().charAt(0)) || PRIMITIVES.contains(tokens.get(i - 2).text()))) {
      return true; // a cast: (int) (x * 2)
    }
    if (p.equals(")") && i >= 3 && tokens.get(i - 2).kind() == Kind.WORD
        && tokens.get(i - 3).text().equals("(") && PRIMITIVES.contains(tokens.get(i - 2).text())) {
      return true; // (double) x
    }
    if (c.equals("(")) {
      // call or declaration: none; keyword: one
      return prev.kind() == Kind.WORD && SPACED_KEYWORDS.contains(p) && !p.equals("new")
          || prev.kind() == Kind.OPERATOR && BINARY.contains(p) && !unary(tokens, i - 1)
          || p.equals(")") && false;
    }
    if (c.equals("[")) {
      return false;
    }
    if (c.equals("{")) {
      return !p.equals("(") && !p.equals("{");
    }
    if (p.equals("{") || c.equals("}")) {
      return !(p.equals("{") && c.equals("}"));
    }
    if (p.equals("}")) {
      return true;
    }
    if (c.equals("<") && genericOpen(tokens, i) || p.equals("<") && genericOpen(tokens, i - 1)) {
      return false;
    }
    if (c.equals(">") && genericClose(tokens, i)) {
      return false;
    }
    if (p.equals(">") && genericClose(tokens, i - 1)) {
      return cur.kind() == Kind.WORD || c.equals("{");
    }
    if (c.equals(":") || p.equals(":")) {
      // ternary/for-each spaced; labels and case keep "case X:"
      return !(c.equals(":") && isCaseColon(tokens, i));
    }
    if (cur.kind() == Kind.OPERATOR && (c.equals("++") || c.equals("--"))) {
      return true;
    }
    if (prev.kind() == Kind.OPERATOR && (p.equals("++") || p.equals("--"))) {
      return !isOperand(cur) || unaryPrefix(tokens, i - 1) ? !unaryPrefix(tokens, i - 1) : true;
    }
    if (prev.kind() == Kind.OPERATOR && (p.equals("-") || p.equals("+")) && unary(tokens, i - 1)) {
      return false;
    }
    if (BINARY.contains(c) || c.equals(">>") || c.equals(">>>") || c.equals("<") || c.equals(">")) {
      return true;
    }
    if (BINARY.contains(p) || p.equals("<") || p.equals(">")) {
      return true;
    }
    return true;
  }

  private static boolean isOperand(Token t) {
    return t.kind() == Kind.WORD || t.kind() == Kind.NUMBER || t.kind() == Kind.LITERAL
        || t.text().equals(")") || t.text().equals("]");
  }

  /** A + or - that has no left operand (x = -1, f(-y), return -z). */
  private static boolean unary(List<Token> tokens, int i) {
    if (i == 0) {
      return true;
    }
    Token before = tokens.get(i - 1);
    return !isOperand(before) || before.kind() == Kind.WORD
        && (before.text().equals("return") || before.text().equals("case"));
  }

  private static boolean unaryPrefix(List<Token> tokens, int i) {
    return i + 1 < tokens.size() && isOperand(tokens.get(i + 1))
        && (i == 0 || !isOperand(tokens.get(i - 1)));
  }

  /** "<" of a type argument list: List<String>, new ArrayList<>(), Map<K, V>. */
  private static boolean genericOpen(List<Token> tokens, int i) {
    if (i == 0 || tokens.get(i - 1).kind() != Kind.WORD) {
      return false;
    }
    String before = tokens.get(i - 1).text();
    if (!Character.isUpperCase(before.charAt(0))) {
      return false;
    }
    int depth = 0;
    for (int j = i; j < tokens.size(); j++) {
      String t = tokens.get(j).text();
      if (t.equals("<")) {
        depth++;
      } else if (t.equals(">")) {
        if (--depth == 0) {
          return true;
        }
      } else if (t.equals(">>")) {
        depth -= 2;
        if (depth <= 0) {
          return true;
        }
      } else if (!(tokens.get(j).kind() == Kind.WORD || t.equals(",") || t.equals("?")
          || t.equals(".") || t.equals("[") || t.equals("]") || t.equals("&"))) {
        return false;
      }
    }
    return false;
  }

  private static boolean genericClose(List<Token> tokens, int i) {
    int depth = 0;
    for (int j = i; j >= 0; j--) {
      String t = tokens.get(j).text();
      if (t.equals(">")) {
        depth++;
      } else if (t.equals("<")) {
        if (--depth == 0) {
          return genericOpen(tokens, j);
        }
      } else if (!(tokens.get(j).kind() == Kind.WORD || t.equals(",") || t.equals("?")
          || t.equals(".") || t.equals("[") || t.equals("]") || t.equals("&"))) {
        return false;
      }
    }
    return false;
  }

  private static boolean isCaseColon(List<Token> tokens, int colon) {
    for (int j = colon - 1; j >= 0; j--) {
      String t = tokens.get(j).text();
      if (t.equals("case") || t.equals("default")) {
        return true;
      }
      if (t.equals("?") || t.equals(";") || t.equals("{") || t.equals("(")) {
        return false;
      }
    }
    // "label:" alone
    return colon == 1;
  }

  /** Tokens per source line; multi-line literals/comments stay on their first line. */
  private static List<List<Token>> lex(String source) {
    List<List<Token>> lines = new ArrayList<>();
    List<Token> line = new ArrayList<>();
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (c == '\n') {
        lines.add(line);
        line = new ArrayList<>();
        i++;
      } else if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
        i++;
      } else if (source.startsWith("//", i)) {
        int end = source.indexOf('\n', i);
        end = end < 0 ? n : end;
        line.add(new Token(Kind.COMMENT, source.substring(i, end).stripTrailing()));
        i = end;
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        end = end < 0 ? n : end + 2;
        i = addMultiLine(source.substring(i, end), Kind.COMMENT, line, lines, i, end);
        line = lines.remove(lines.size() - 1);
      } else if (source.startsWith("\"\"\"", i)) {
        int end = source.indexOf("\"\"\"", i + 3);
        while (end > 0 && source.charAt(end - 1) == '\\' && !escapedBackslash(source, end - 1)) {
          end = source.indexOf("\"\"\"", end + 1);
        }
        end = end < 0 ? n : end + 3;
        i = addMultiLine(source.substring(i, end), Kind.LITERAL, line, lines, i, end);
        line = lines.remove(lines.size() - 1);
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
          j += source.charAt(j) == '\\' ? 2 : 1;
        }
        j = Math.min(n, j + 1);
        line.add(new Token(Kind.LITERAL, source.substring(i, j)));
        i = j;
      } else if (Character.isJavaIdentifierStart(c)) {
        int j = i + 1;
        while (j < n && Character.isJavaIdentifierPart(source.charAt(j))) {
          j++;
        }
        line.add(new Token(Kind.WORD, source.substring(i, j)));
        i = j;
      } else if (Character.isDigit(c) || c == '.' && i + 1 < n
          && Character.isDigit(source.charAt(i + 1))) {
        int j = i + 1;
        while (j < n && (Character.isLetterOrDigit(source.charAt(j)) || source.charAt(j) == '.'
            || source.charAt(j) == '_' || (source.charAt(j) == '-' || source.charAt(j) == '+')
                && (source.charAt(j - 1) == 'e' || source.charAt(j - 1) == 'E')
                && !source.substring(i, j).startsWith("0x"))) {
          j++;
        }
        line.add(new Token(Kind.NUMBER, source.substring(i, j)));
        i = j;
      } else {
        String op = String.valueOf(c);
        for (String candidate : OPERATORS) {
          if (source.startsWith(candidate, i)) {
            op = candidate;
            break;
          }
        }
        // >> and >>> are left as single '>' tokens: generics close with them
        line.add(new Token(Kind.OPERATOR, op));
        i += op.length();
      }
    }
    lines.add(line);
    // drop the final empty line produced by a trailing newline
    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1);
    }
    return lines;
  }

  private static boolean escapedBackslash(String source, int index) {
    int count = 0;
    for (int k = index; k >= 0 && source.charAt(k) == '\\'; k--) {
      count++;
    }
    return count % 2 == 0;
  }

  /**
   * A block comment or text block spanning lines: its first line joins the
   * current line, every further line becomes a raw line of its own.
   */
  private static int addMultiLine(String text, Kind kind, List<Token> line,
      List<List<Token>> lines, int start, int end) {
    String[] parts = text.split("\n", -1);
    line.add(new Token(kind, parts[0].stripTrailing()));
    for (int k = 1; k < parts.length; k++) {
      lines.add(line);
      line = new ArrayList<>();
      line.add(new Token(kind == Kind.LITERAL ? Kind.RAW_TEXT : Kind.RAW_COMMENT,
          kind == Kind.LITERAL ? parts[k] : parts[k].stripTrailing()));
    }
    lines.add(line);
    return end;
  }
}
