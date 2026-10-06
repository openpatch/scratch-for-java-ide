package org.openpatch.scratch4j.ui;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based syntax highlighting for the languages a Scratch for Java project
 * contains: Java, JSON (fs extension save files), XML/TMX (Tiled maps, atlases)
 * and GLSL (shader extension). Produces style-class spans for the code area;
 * the colours live in studio.css ({@code .code-area .keyword} ...).
 */
final class SyntaxHighlighter {

  enum Language { JAVA, JSON, XML, GLSL, PLAIN }

  private static final String[] JAVA_KEYWORDS = {
      "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
      "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
      "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
      "interface", "long", "native", "new", "package", "private", "protected", "public",
      "record", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
      "this", "throw", "throws", "transient", "try", "var", "void", "volatile", "while",
      "yield", "true", "false", "null"};

  /** A Java keyword (no link, nothing to go to). */
  static boolean isKeyword(String word) {
    return java.util.Arrays.asList(JAVA_KEYWORDS).contains(word);
  }

  private static final String[] GLSL_KEYWORDS = {
      "attribute", "const", "uniform", "varying", "layout", "in", "out", "inout", "float",
      "int", "uint", "void", "bool", "true", "false", "break", "continue", "do", "for",
      "while", "if", "else", "discard", "return", "struct", "precision", "highp", "mediump",
      "lowp", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2", "bvec3", "bvec4",
      "mat2", "mat3", "mat4", "sampler2D", "samplerCube", "#version", "#ifdef", "#endif",
      "#define", "#else", "#if"};

  private static final Pattern JAVA = Pattern.compile(
      "(?<COMMENT>//[^\n]*|/\\*(.|\\R)*?\\*/)"
          + "|(?<STRING>\"\"\"(.|\\R)*?\"\"\"|\"([^\"\\\\\n]|\\\\.)*\"|'([^'\\\\\n]|\\\\.)*')"
          + "|(?<ANNOTATION>@\\w+)"
          + "|(?<KEYWORD>\\b(" + String.join("|", JAVA_KEYWORDS) + ")\\b)"
          + "|(?<TYPE>\\b[A-Z][A-Za-z0-9_]*\\b)"
          + "|(?<NUMBER>\\b\\d+(\\.\\d+)?[fFdDlL]?\\b)"
          + "|(?<METHOD>\\b[a-z_][A-Za-z0-9_]*(?=\\s*\\())"
          + "|(?<BRACE>[{}])|(?<PAREN>[()])|(?<BRACKET>[\\[\\]])|(?<SEMICOLON>;)");

  private static final Pattern JSON = Pattern.compile(
      "(?<KEY>\"([^\"\\\\\n]|\\\\.)*\"(?=\\s*:))"
          + "|(?<STRING>\"([^\"\\\\\n]|\\\\.)*\")"
          + "|(?<NUMBER>-?\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b)"
          + "|(?<KEYWORD>\\b(true|false|null)\\b)"
          + "|(?<BRACE>[{}\\[\\]])");

  private static final Pattern XML = Pattern.compile(
      "(?<COMMENT><!--(.|\\R)*?-->)"
          + "|(?<TAG></?[A-Za-z_][\\w:.-]*|/?>|<\\?xml|\\?>)"
          + "|(?<ATTR>\\b[A-Za-z_][\\w:.-]*(?=\\s*=))"
          + "|(?<STRING>\"[^\"]*\"|'[^']*')");

  private static final Pattern GLSL = Pattern.compile(
      "(?<COMMENT>//[^\n]*|/\\*(.|\\R)*?\\*/)"
          + "|(?<KEYWORD>(?<![\\w#])(" + String.join("|", GLSL_KEYWORDS)
              .replace("#", "\\#") + ")\\b)"
          + "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)"
          + "|(?<METHOD>\\b[a-z_][A-Za-z0-9_]*(?=\\s*\\())"
          + "|(?<BRACE>[{}])|(?<PAREN>[()])|(?<SEMICOLON>;)");

  private static final List<String> GROUPS = List.of("COMMENT", "STRING", "ANNOTATION",
      "KEYWORD", "TYPE", "NUMBER", "METHOD", "BRACE", "PAREN", "BRACKET", "SEMICOLON",
      "KEY", "TAG", "ATTR");

  private SyntaxHighlighter() {}

  static Language languageOf(Path file) {
    String name = file == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".java")) {
      return Language.JAVA;
    }
    if (name.endsWith(".json")) {
      return Language.JSON;
    }
    if (name.matches(".*\\.(xml|tmx|tsx)$")) {
      return Language.XML;
    }
    if (name.matches(".*\\.(glsl|frag|vert)$")) {
      return Language.GLSL;
    }
    return Language.PLAIN;
  }

  /** True for files the code editor opens as text. */
  static boolean isText(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return languageOf(file) != Language.PLAIN
        || name.matches(".*\\.(txt|md|properties|csv|bluej|gitignore)$")
        || name.equals("package.bluej");
  }

  static StyleSpans<Collection<String>> highlight(String text, Language language) {
    Pattern pattern = switch (language) {
      case JAVA -> JAVA;
      case JSON -> JSON;
      case XML -> XML;
      case GLSL -> GLSL;
      case PLAIN -> null;
    };
    StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
    if (pattern == null) {
      spans.add(Collections.emptyList(), text.length());
      return spans.create();
    }
    Matcher m = pattern.matcher(text);
    int last = 0;
    while (m.find()) {
      String style = styleOf(m);
      spans.add(Collections.emptyList(), m.start() - last);
      spans.add(Collections.singleton(style), m.end() - m.start());
      last = m.end();
    }
    spans.add(Collections.emptyList(), text.length() - last);
    return spans.create();
  }

  private static String styleOf(Matcher m) {
    for (String group : GROUPS) {
      try {
        if (m.group(group) != null) {
          return group.toLowerCase(Locale.ROOT);
        }
      } catch (IllegalArgumentException noSuchGroup) {
        // group not defined in this language's pattern
      }
    }
    return "plain";
  }
}
