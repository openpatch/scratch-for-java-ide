package org.openpatch.scratch4j.core.region;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Adds the managed regions the visual editors write into to classes written
 * without the IDE (the library's demos and tutorials, imported projects):
 *
 * <ul>
 *   <li>a stage gets {@code fields} at the top of the class and {@code setup}
 *       at the end of its constructor,</li>
 *   <li>a sprite gets {@code setup} at the end of its constructor,</li>
 *   <li>a window gets {@code window} at the end of its constructor and
 *       {@code options} in {@code main} before the window is created; the
 *       settings the dialog understands ({@code Window.useFullScreen()},
 *       texture sampling, splash logo) move into it.</li>
 * </ul>
 *
 * The regions start empty, so the program does exactly what it did before. A
 * class without a constructor gets an empty public one (what Java gave it
 * implicitly). Classes that already have their regions are left as they are.
 */
public final class DesignerRegions {

  private static final String STAGE_NOTE = " (managed by the stage designer)";
  private static final Pattern CLASS = Pattern.compile(
      "(?m)^([ \\t]*)public\\s+class\\s+(\\w+)\\s+extends\\s+(Stage|Sprite|AnimatedSprite|"
          + "UISprite|Window)\\b[^{]*\\{[ \\t]*\\n");

  private DesignerRegions() {}

  /** Any public class with a superclass (for classes whose kind comes from the project). */
  private static final Pattern ANY_CLASS = Pattern.compile(
      "(?m)^([ \\t]*)public\\s+(?:abstract\\s+)?class\\s+(\\w+)\\s+extends\\s+(\\w+)\\b[^{]*"
          + "\\{[ \\t]*\\n");

  /** The source with the regions its class kind needs (unchanged when nothing is missing). */
  public static String ensure(String source) {
    Matcher type = CLASS.matcher(source);
    if (!type.find()) {
      return source;
    }
    String kind = type.group(3);
    return ensure(source, kind.equals("Stage") || kind.equals("Window") ? kind : "Sprite");
  }

  /** A sprite class, also one extending a project class ({@code Bamboo extends Enemy}). */
  public static String ensureSprite(String source) {
    return ensure(source, "Sprite");
  }

  /** A stage class, also one extending a project stage class. */
  public static String ensureStage(String source) {
    return ensure(source, "Stage");
  }

  /**
   * The regions of a class of {@code kind} ({@code Stage}, {@code Sprite},
   * {@code Window}), whatever its direct superclass is.
   */
  public static String ensure(String source, String kind) {
    Matcher type = ANY_CLASS.matcher(source);
    if (!type.find()) {
      return source;
    }
    String name = type.group(2);
    String result = source;
    if (kind.equals("Window")) {
      if (!RegionParser.has(result, WindowDocument.OPTIONS)) {
        result = addOptions(result, name);
      }
      if (!RegionParser.has(result, WindowDocument.SETUP)) {
        result = addToConstructor(result, name, WindowDocument.SETUP, WindowDocument.MANAGED);
        result = pullIntoWindowRegion(result);
      }
      return result;
    }
    if (kind.equals("Stage") && !RegionParser.has(result, "fields")) {
      Matcher again = ANY_CLASS.matcher(result);
      again.find();
      String indent = again.group(1) + memberIndent(result, again.end());
      result = result.substring(0, again.end()) + "\n" + indent + "// scratch4j:begin fields"
          + STAGE_NOTE + "\n" + indent + "// scratch4j:end fields\n"
          + result.substring(again.end());
    }
    if (!RegionParser.has(result, "setup")) {
      // around the statements the visual editors manage, where they are; else at the end
      String wrapped = wrapManaged(result, name, kind.equals("Stage"));
      result = wrapped != null ? wrapped : addToConstructor(result, name, "setup", STAGE_NOTE);
    }
    return result;
  }

  /** What the sprite editor manages: literal asset calls and the sprite's own settings. */
  private static final Pattern SPRITE_MANAGED = Pattern.compile(
      "(?:this\\.)?(?:addCostume|addCostumes|addAnimation|addSound)\\(\\s*\"[^\"]*\""
          + "(?:\\s*,\\s*(?:\"[^\"]*\"|-?\\d+(?:\\.\\d+)?|true|false))*\\s*\\)\\s*;"
          + "|(?:this\\.)?(?:setHitbox|setRotationCenter|setNineSlice|setAnimationInterval)"
          + "\\(\\s*-?\\d+(?:\\.\\d+)?(?:\\s*,\\s*-?\\d+(?:\\.\\d+)?)*\\s*\\)\\s*;");

  /**
   * Wraps the longest run of the first constructor's statements that the
   * visual editor manages in the setup region (sprites: literal asset calls,
   * hitbox, rotation centre, nine-slice, animation interval; stages: what the
   * stage designer reads). Nothing moves. Null when no such statement exists.
   */
  static String wrapManaged(String source, String name, boolean stage) {
    // the constructor with the longest run (a delegating Donut() has none of its own)
    String best = null;
    int bestCount = 0;
    Matcher ctor = Pattern.compile("(?m)^[ \\t]*(?:public|protected|private)?\\s*"
        + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{")
        .matcher(source);
    while (ctor.find()) {
      int close = matchingBrace(source, ctor.end() - 1);
      if (close < 0) continue;
      int[] count = {0};
      String wrapped = wrapIn(source, new int[] {ctor.end(), close}, name, stage, count);
      if (wrapped != null && count[0] > bestCount) {
        best = wrapped;
        bestCount = count[0];
      }
    }
    return best;
  }

  private static String wrapIn(String source, int[] body, String name, boolean stage,
      int[] found) {
    // the statements as lines: [start of line, end of line incl. newline, managed?]
    List<int[]> lines = new ArrayList<>();
    int at = source.indexOf('\n', body[0]) + 1;
    while (at > 0 && at < body[1]) {
      int end = source.indexOf('\n', at);
      if (end < 0 || end >= body[1]) break;
      lines.add(new int[] {at, end + 1});
      at = end + 1;
    }
    int bestFrom = -1;
    int bestTo = -1;
    int bestCount = 0;
    for (int i = 0; i < lines.size(); i++) {
      if (!statementLine(source, lines.get(i))) continue;
      for (int j = i; j < lines.size(); j++) {
        String line = text(source, lines.get(j)).trim();
        if (!line.isEmpty() && !statementLine(source, lines.get(j))) break;
        if (line.isEmpty()) continue;
        if (!managed(source, lines, i, j, name, stage)) break;
        int count = j - i + 1;
        if (count > bestCount) {
          bestCount = count;
          bestFrom = i;
          bestTo = j;
        }
      }
    }
    if (bestFrom < 0) return null;
    found[0] = bestCount;
    int start = lines.get(bestFrom)[0];
    int end = lines.get(bestTo)[1];
    String indent = text(source, lines.get(bestFrom)).replaceAll("\\S.*", "");
    return source.substring(0, start) + indent + "// scratch4j:begin setup" + STAGE_NOTE + "\n"
        + source.substring(start, end) + indent + "// scratch4j:end setup\n"
        + source.substring(end);
  }

  private static String text(String source, int[] line) {
    return source.substring(line[0], line[1] - 1);
  }

  /** A line holding exactly one simple statement (no blocks, no comments). */
  private static boolean statementLine(String source, int[] line) {
    String t = text(source, line).trim();
    return t.endsWith(";") && !t.startsWith("//") && !t.contains("{") && !t.contains("}");
  }

  /** Whether lines i..j, wrapped, are all the visual editor's. */
  private static boolean managed(String source, List<int[]> lines, int i, int j, String name,
      boolean stage) {
    if (!stage) {
      for (int k = i; k <= j; k++) {
        String t = text(source, lines.get(k)).trim();
        if (!t.isEmpty() && !SPRITE_MANAGED.matcher(t).matches()) return false;
      }
      return true;
    }
    // the stage designer reads it (and finds something in it)
    String trial = source.substring(0, lines.get(i)[0]) + "// scratch4j:begin setup\n"
        + source.substring(lines.get(i)[0], lines.get(j)[1]) + "// scratch4j:end setup\n"
        + source.substring(lines.get(j)[1]);
    try {
      StageModel model = StageDocument.read(trial).model();
      return !model.backdrops().isEmpty() || !model.sprites().isEmpty() || !model.sounds().isEmpty();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Adds the regions to every class in the project folder (bundled templates). */
  public static void ensureAll(Path root) throws IOException {
    List<Path> sources;
    try (Stream<Path> files = Files.list(root)) {
      sources = files.filter(p -> p.toString().endsWith(".java")).toList();
    }
    for (Path file : sources) {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      String kind = kindOf(root, file.getFileName().toString().replaceFirst("\\.java$", ""));
      if (kind == null) continue;
      String updated = ensure(source, kind);
      if (!updated.equals(source)) {
        Files.writeString(file, updated, StandardCharsets.UTF_8);
      }
    }
  }

  /**
   * {@code Stage}, {@code Sprite} or {@code Window} for a class of the
   * project, following its superclasses through the project's files
   * ({@code Bamboo extends Enemy extends AnimatedSprite} is a sprite); null
   * for other classes.
   */
  public static String kindOf(Path root, String className) {
    String name = className;
    for (int depth = 0; depth < 12 && name != null; depth++) {
      switch (name) {
        case "Stage" -> {
          return depth == 0 ? null : "Stage";
        }
        case "Window" -> {
          return depth == 0 ? null : "Window";
        }
        case "Sprite", "AnimatedSprite", "UISprite" -> {
          return depth == 0 ? null : "Sprite";
        }
        default -> { }
      }
      Path file = root.resolve(name + ".java");
      if (!Files.isRegularFile(file)) return null;
      try {
        Matcher m = Pattern.compile("\\bclass\\s+" + Pattern.quote(name)
            + "\\s+extends\\s+(\\w+)").matcher(Files.readString(file, StandardCharsets.UTF_8));
        name = m.find() ? m.group(1) : null;
      } catch (IOException e) {
        return null;
      }
    }
    return null;
  }

  private static String addToConstructor(String source, String name, String id, String note) {
    int[] body = constructorBody(source, name);
    if (body == null) {
      // no constructor: an explicit empty one is what Java generated anyway
      Matcher type = ANY_CLASS.matcher(source);
      type.find();
      String indent = type.group(1) + memberIndent(source, type.end());
      String inner = indent + "  ";
      int at = afterFieldsRegion(source, type.end());
      return source.substring(0, at) + indent + "public " + name + "() {\n" + inner
          + "// scratch4j:begin " + id + note + "\n" + inner + "// scratch4j:end " + id + "\n"
          + indent + "}\n\n" + source.substring(at);
    }
    // before the constructor's closing brace, indented like its statements
    int close = body[1];
    int lineStart = source.lastIndexOf('\n', close - 1) + 1;
    boolean braceOnOwnLine = lineStart > body[0]
        && source.substring(lineStart, close).isBlank();
    String closeIndent = braceOnOwnLine ? source.substring(lineStart, close) : "";
    String inner = statementIndent(source, body[0], close, closeIndent + "  ");
    String region = inner + "// scratch4j:begin " + id + note + "\n" + inner
        + "// scratch4j:end " + id + "\n";
    if (braceOnOwnLine) {
      return source.substring(0, lineStart) + "\n" + region + source.substring(lineStart);
    }
    return source.substring(0, close) + "\n" + region + closeIndent + source.substring(close);
  }

  /**
   * The constructor's last statements, when they are the start stage
   * ({@code this.setStage(new MyStage());}) or the debug switch, move into the
   * window region right after them: same order, and the start star edits them.
   */
  private static String pullIntoWindowRegion(String source) {
    Region region = RegionParser.find(source, WindowDocument.SETUP);
    int beginLine = source.lastIndexOf('\n', region.beginOffset() - 1);
    beginLine = source.lastIndexOf('\n', beginLine - 1) + 1; // start of the begin marker
    String before = source.substring(0, beginLine);
    List<String> lines = new ArrayList<>(List.of(before.split("\n", -1)));
    lines.remove(lines.size() - 1); // after the last newline
    List<String> moved = new ArrayList<>();
    while (!lines.isEmpty()) {
      String line = lines.get(lines.size() - 1).trim();
      if (line.isEmpty()) {
        lines.remove(lines.size() - 1);
      } else if (line.matches("this\\.setStage\\(new\\s+\\w+\\(\\)\\)\\s*;")
          || line.matches("this\\.setDebug\\((true|false)\\)\\s*;")) {
        moved.add(0, line);
        lines.remove(lines.size() - 1);
      } else {
        break;
      }
    }
    if (moved.isEmpty()) {
      return source;
    }
    StringBuilder body = new StringBuilder();
    for (String line : moved) {
      body.append(region.indent()).append(line).append('\n');
    }
    String head = String.join("\n", lines) + "\n\n";
    return RegionParser.rewrite(head + source.substring(beginLine), WindowDocument.SETUP,
        body.toString());
  }

  /** {@code Window.useFullScreen()} and the other dialog settings move into the region. */
  private static String addOptions(String source, String name) {
    Matcher main = Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\([^)]*\\)\\s*\\{")
        .matcher(source);
    if (!main.find()) {
      return source;
    }
    int end = matchingBrace(source, main.end() - 1);
    if (end < 0) {
      return source;
    }
    String body = source.substring(main.end(), end);
    Matcher create = Pattern.compile("(?m)^([ \\t]*)new\\s+" + Pattern.quote(name) + "\\s*\\(")
        .matcher(body);
    if (!create.find()) {
      return source;
    }
    String indent = create.group(1);
    List<String> moved = new ArrayList<>();
    StringBuilder kept = new StringBuilder();
    for (String line : body.substring(0, create.start()).split("\n", -1)) {
      String trimmed = line.trim();
      if (trimmed.matches("(?:org\\.openpatch\\.scratch\\.)?Window\\.(useFullScreen\\(\\)"
          + "|useTextureSampling\\((?:org\\.openpatch\\.scratch\\.)?TextureSampling\\.\\w+\\)"
          + "|useSplashLogo\\(\"[^\"\\\\]*\"\\));")) {
        moved.add(trimmed);
      } else {
        kept.append(line).append('\n');
      }
    }
    String keptText = kept.substring(0, kept.length() - 1);
    StringBuilder region = new StringBuilder();
    region.append(indent).append("// scratch4j:begin options").append(WindowDocument.MANAGED).append('\n');
    for (String line : moved) {
      region.append(indent).append(line).append('\n');
    }
    region.append(indent).append("// scratch4j:end options\n").append(indent);
    String newBody = keptText + region + body.substring(create.start() + indent.length());
    return source.substring(0, main.end()) + newBody + source.substring(end);
  }

  private static int afterFieldsRegion(String source, int classBodyStart) {
    if (RegionParser.has(source, "fields")) {
      Region fields = RegionParser.find(source, "fields");
      int lineEnd = source.indexOf('\n', fields.endOffset());
      return lineEnd < 0 ? source.length() : lineEnd + 1;
    }
    return classBodyStart;
  }

  /** {start of body after '{', offset of the closing '}'} of the first constructor. */
  private static int[] constructorBody(String source, String name) {
    Matcher ctor = Pattern.compile("(?m)^[ \\t]*(?:public|protected|private)?\\s*"
        + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{").matcher(source);
    if (!ctor.find()) {
      return null;
    }
    int close = matchingBrace(source, ctor.end() - 1);
    return close < 0 ? null : new int[] {ctor.end(), close};
  }

  /** The '}' matching the '{' at {@code open}, skipping strings, chars and comments. */
  static int matchingBrace(String s, int open) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
        i = s.indexOf('\n', i);
        if (i < 0) return -1;
      } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
        i = s.indexOf("*/", i + 2);
        if (i < 0) return -1;
        i++;
      } else if (c == '"' && s.startsWith("\"\"\"", i)) {
        i = s.indexOf("\"\"\"", i + 3);
        if (i < 0) return -1;
        i += 2;
      } else if (c == '"' || c == '\'') {
        for (i++; i < s.length() && s.charAt(i) != c; i++) {
          if (s.charAt(i) == '\\') i++;
        }
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  private static String memberIndent(String source, int from) {
    Matcher line = Pattern.compile("(?m)^([ \\t]+)\\S").matcher(source);
    return line.find(from) ? line.group(1) : "  ";
  }

  private static String statementIndent(String source, int from, int to, String fallback) {
    Matcher line = Pattern.compile("(?m)^([ \\t]+)\\S").matcher(source);
    line.region(from, to);
    return line.find() ? line.group(1) : fallback;
  }
}
