package org.openpatch.scratch4j.core.lint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.lang.model.SourceVersion;

/** Contextual spelling checks for type declarations, before javac's recovery errors. */
final class KeywordTypos {
  private static final List<String> TYPES = List.of("class", "interface", "enum", "record");
  private static final List<String> MODIFIERS = List.of(
      "public", "protected", "private", "static", "abstract", "final", "sealed", "strictfp");

  record Typo(String word, String keyword, String className, String parent, int start, int end) {}
  private record Token(String text, int start, int end) {
    boolean identifier() { return SourceVersion.isIdentifier(text) && !SourceVersion.isKeyword(text); }
  }

  private KeywordTypos() {}

  static Typo find(String source, long line, long column) {
    int position = position(source, line, column);
    if (position < 0) return null;
    List<Token> tokens = tokens(source);
    for (int i = 0; i + 1 < tokens.size(); i++) {
      Token kind = tokens.get(i), name = tokens.get(i + 1);
      String type = closest(kind.text(), TYPES);
      if (type == null || !name.identifier()) continue;
      int afterName = skipGenerics(tokens, i + 2);
      // "cass world;" could be a field of a student-defined type named cass.
      if (!kind.text().equals(type) && (afterName >= tokens.size()
          || !(tokens.get(afterName).text().equals("{")
              || closest(tokens.get(afterName).text(), List.of("extends", "implements", "permits")) != null))) continue;

      var typos = new ArrayList<Typo>();
      if (!kind.text().equals(type)) typos.add(typo(kind, type, name.text(), ""));
      int before = i - 1;
      if (type.equals("interface") && before >= 0 && tokens.get(before).text().equals("@")) before--;
      while (before >= 0) {
        Token modifier = tokens.get(before);
        if (before >= 2 && modifier.text().equals("sealed") && tokens.get(before - 1).text().equals("-")
            && tokens.get(before - 2).text().equals("non")) { before -= 3; continue; }
        String keyword = closest(modifier.text(), MODIFIERS);
        if (keyword != null) {
          if (!modifier.text().equals(keyword)) typos.add(typo(modifier, keyword, name.text(), ""));
          before--;
        } else {
          int annotation = beforeAnnotation(tokens, before);
          if (annotation == before) break;
          before = annotation;
        }
      }
      if (before >= 0 && !List.of(";", "{", "}").contains(tokens.get(before).text())) continue;
      if (!typos.isEmpty()) {
        Typo first = typos.stream().min(Comparator.comparingInt(Typo::start)).orElseThrow();
        if (position >= first.start() && position <= name.end()) return first;
      }

      List<String> clauses = switch (type) {
        case "interface" -> List.of("extends");
        case "enum", "record" -> List.of("implements");
        default -> List.of("extends", "implements");
      };
      int previousEnd = tokens.get(afterName - 1).end();
      for (int at = afterName; at + 1 < tokens.size();) {
        Token word = tokens.get(at), parent = tokens.get(at + 1);
        String keyword = closest(word.text(), clauses);
        if (keyword == null || !parent.identifier()) break;
        int end = typeEnd(tokens, at + 1);
        if (!word.text().equals(keyword) && position >= previousEnd && position <= word.end()) {
          String parentName = source.substring(parent.start(), tokens.get(end - 1).end());
          return typo(word, keyword, name.text(), parentName);
        }
        at = end;
        while (at + 1 < tokens.size() && tokens.get(at).text().equals(",") && tokens.get(at + 1).identifier())
          at = typeEnd(tokens, at + 1);
        previousEnd = tokens.get(at - 1).end();
      }
    }
    return null;
  }

  private static Typo typo(Token word, String keyword, String name, String parent) {
    return new Typo(word.text(), keyword, name, parent, word.start(), word.end());
  }

  /** One missing, extra, replaced or swapped letter, or a casing error; ties stay unguessed. */
  private static String closest(String word, List<String> choices) {
    if (choices.contains(word)) return word;
    String result = null;
    for (String keyword : choices) {
      if (Math.abs(word.length() - keyword.length()) > 1
          || DidYouMean.distance(word.toLowerCase(Locale.ROOT), keyword) > 1) continue;
      if (result != null) return null;
      result = keyword;
    }
    return result;
  }

  private static int skipGenerics(List<Token> tokens, int at) {
    if (at >= tokens.size() || !tokens.get(at).text().equals("<")) return at;
    int depth = 0;
    do {
      String word = tokens.get(at++).text();
      if (word.equals("<")) depth++;
      if (word.equals(">")) depth--;
      if (word.equals("{") || word.equals(";") || word.equals("}")) break;
    } while (at < tokens.size() && depth > 0);
    return at;
  }

  private static int typeEnd(List<Token> tokens, int at) {
    at = skipGenerics(tokens, at + 1);
    while (at + 1 < tokens.size() && tokens.get(at).text().equals(".") && tokens.get(at + 1).identifier())
      at = skipGenerics(tokens, at + 2);
    return at;
  }

  private static int beforeAnnotation(List<Token> tokens, int before) {
    int at = before;
    if (tokens.get(at).text().equals(")")) {
      int depth = 1;
      while (--at >= 0 && depth > 0) {
        if (tokens.get(at).text().equals(")")) depth++;
        if (tokens.get(at).text().equals("(")) depth--;
      }
      if (depth != 0) return before;
    }
    if (at < 0 || !tokens.get(at).identifier()) return before;
    while (at >= 2 && tokens.get(at - 1).text().equals(".") && tokens.get(at - 2).identifier()) at -= 2;
    return at > 0 && tokens.get(at - 1).text().equals("@") ? at - 2 : before;
  }

  private static int position(String source, long line, long column) {
    if (line < 1 || column < 1) return -1;
    int offset = 0;
    for (long i = 1; i < line; i++) {
      int newline = source.indexOf('\n', offset);
      if (newline < 0) return -1;
      offset = newline + 1;
    }
    // javac expands tabs to eight-column stops in its diagnostics.
    long currentColumn = 1;
    for (int at = offset; at <= source.length(); at++) {
      if (currentColumn == column) return at;
      if (at == source.length() || source.charAt(at) == '\n' || currentColumn > column) return -1;
      currentColumn += source.charAt(at) == '\t' ? 8 - (currentColumn - 1) % 8 : 1;
    }
    return -1;
  }

  /** Keep offsets, and treat literals as opaque tokens so examples inside them cannot become fixes. */
  private static List<Token> tokens(String source) {
    var tokens = new ArrayList<Token>();
    for (int at = 0; at < source.length();) {
      int start = at;
      char c = source.charAt(at++);
      if (Character.isWhitespace(c)) continue;
      if (source.startsWith("//", start)) {
        while (at < source.length() && source.charAt(at) != '\n') at++;
        continue;
      }
      if (source.startsWith("/*", start)) {
        int end = source.indexOf("*/", at + 1);
        at = end < 0 ? source.length() : end + 2;
        continue;
      }
      if (c == '"' || c == '\'') {
        String closing = source.startsWith("\"\"\"", start) ? "\"\"\"" : String.valueOf(c);
        at = start + closing.length();
        while (at < source.length()) {
          if (source.charAt(at) == '\\') { at = Math.min(at + 2, source.length()); continue; }
          if (source.startsWith(closing, at)) { at += closing.length(); break; }
          at++;
        }
        tokens.add(new Token("<literal>", start, at));
        continue;
      }
      if (Character.isJavaIdentifierStart(source.codePointAt(start))) {
        at = start + Character.charCount(source.codePointAt(start));
        while (at < source.length() && Character.isJavaIdentifierPart(source.codePointAt(at)))
          at += Character.charCount(source.codePointAt(at));
      }
      tokens.add(new Token(source.substring(start, at), start, at));
    }
    return tokens;
  }
}
