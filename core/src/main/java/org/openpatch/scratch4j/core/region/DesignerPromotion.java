package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-written sprite creations in a stage's constructor that the stage
 * designer can take over ("make editable in the designer"):
 *
 * <pre>
 * this.add(new Basket());                       ->  basket = new Basket(); this.add(basket);
 * var host = new Sprite();                      ->  host = new Sprite();
 * host.addCostume("alienGreen_stand");              host.addCostume("alienGreen_stand");
 * host.setY(-20);                                   host.setPosition(0, -20);
 * this.add(host);                                   this.add(host);
 * </pre>
 *
 * The sprite becomes a field in the {@code fields} region and its lines move
 * into the {@code setup} region. Only when that keeps the drawing order (no
 * other {@code add(...)} between them and the region) and the designer reads
 * the result.
 */
public final class DesignerPromotion {

  /** A sprite the designer can take over: its lines (1-based, inclusive) and name. */
  public record Promotion(int firstLine, int lastLine, String name, String type) {}

  private static final List<Pattern> ALLOWED = List.of(
      Pattern.compile("(\\w+)\\.addCostume\\(\"[^\"]+\"\\);"),
      Pattern.compile("(\\w+)\\.switchCostume\\(\"[^\"]+\"\\);"),
      Pattern.compile("(\\w+)\\.setDirection\\(-?\\d+(?:\\.\\d+)?\\);"),
      Pattern.compile("(\\w+)\\.setSize\\(-?\\d+(?:\\.\\d+)?\\);"),
      Pattern.compile("(\\w+)\\.setWidth\\(-?\\d+(?:\\.\\d+)?\\);"),
      Pattern.compile("(\\w+)\\.setHeight\\(-?\\d+(?:\\.\\d+)?\\);"),
      Pattern.compile("(\\w+)\\.setRotationStyle\\((?:org\\.openpatch\\.scratch\\.)?RotationStyle\\.\\w+\\);"),
      Pattern.compile("(\\w+)\\.(?:hide|show)\\(\\);"));
  private static final Pattern POS = Pattern.compile("(\\w+)\\.(setX|setY)\\((-?\\d+(?:\\.\\d+)?)\\);");
  private static final Pattern SETPOS =
      Pattern.compile("(\\w+)\\.setPosition\\((-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)\\);");
  private static final Pattern ADD_NEW = Pattern.compile("this\\.add\\(new ([A-Z]\\w*)\\(\\)\\);");
  private static final Pattern CREATE =
      Pattern.compile("(?:(?:final\\s+)?(\\w+)\\s+)?(\\w+)\\s*=\\s*new ([A-Z]\\w*)\\(\\);");
  private static final List<String> NOT_SPRITES = List.of("Text", "Vector2", "Random", "Color",
      "Timer", "Pen", "TiledMap", "ArrayList", "HashMap");

  private DesignerPromotion() {}

  /** Every sprite creation the designer could take over, in source order. */
  public static List<Promotion> find(String source) {
    List<Promotion> out = new ArrayList<>();
    for (Candidate c : candidates(source)) {
      try {
        apply(source, c);
        out.add(c.promotion);
      } catch (RuntimeException e) {
        // the designer could not read it, or the drawing order would change
      }
    }
    return out;
  }

  /** The source with the sprite whose lines start at {@code firstLine} in the designer. */
  public static String apply(String source, int firstLine) {
    for (Candidate c : candidates(source)) {
      if (c.promotion.firstLine() == firstLine) return apply(source, c);
    }
    throw new IllegalArgumentException("Nothing the designer can take over on line " + firstLine);
  }

  private record Candidate(Promotion promotion, List<String> designerLines, String declaration) {}

  private static List<Candidate> candidates(String source) {
    List<Candidate> out = new ArrayList<>();
    if (!RegionParser.has(source, "setup") || !RegionParser.has(source, "fields")) return out;
    String[] lines = source.split("\n", -1);
    Region setup = RegionParser.find(source, "setup");
    int setupLine = lineOf(source, setup.beginOffset());
    String indent = lines[setupLine - 2].replaceAll("\\S.*", "");
    // the constructor holding the region: from its line to the brace that closes it
    int start = setupLine - 1;
    while (start > 0 && !lines[start - 1].contains("{")) start--;
    int end = start;
    int depth = 0;
    for (int i = Math.max(0, start - 1); i < lines.length; i++) {
      for (char ch : lines[i].toCharArray()) {
        if (ch == '{') depth++;
        if (ch == '}') depth--;
      }
      if (depth <= 0 && i >= setupLine) {
        end = i;
        break;
      }
    }
    for (int i = start; i < end; i++) {
      if (!lines[i].startsWith(indent) || lines[i].startsWith(indent + " ")) continue;
      if (i + 1 >= setupLine - 1 && i + 1 <= lineOf(source, setup.endOffset())) continue;
      String t = lines[i].strip();
      Matcher add = ADD_NEW.matcher(t);
      Matcher create = CREATE.matcher(t);
      if (add.matches() && !NOT_SPRITES.contains(add.group(1))) {
        String type = add.group(1);
        String name = Character.toLowerCase(type.charAt(0)) + type.substring(1);
        while (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source).find()) {
          name += "2";
        }
        out.add(new Candidate(new Promotion(i + 1, i + 1, name, type),
            List.of(name + " = new " + type + "();", "this.add(" + name + ");"), null));
      } else if (create.matches() && !NOT_SPRITES.contains(create.group(3))) {
        String decl = create.group(1);
        String var = create.group(2);
        String type = decl == null || decl.equals("var") ? create.group(3) : decl;
        List<String> body = new ArrayList<>();
        body.add(var + " = new " + create.group(3) + "();");
        String x = null;
        String y = null;
        int j = i + 1;
        boolean added = false;
        for (; j < end; j++) {
          String u = lines[j].strip();
          if (u.isEmpty()) continue;
          if (u.equals("this.add(" + var + ");")) {
            added = true;
            break;
          }
          Matcher pos = POS.matcher(u);
          Matcher setPos = SETPOS.matcher(u);
          if (pos.matches() && pos.group(1).equals(var)) {
            if (pos.group(2).equals("setX")) x = pos.group(3); else y = pos.group(3);
            continue;
          }
          if (setPos.matches() && setPos.group(1).equals(var)) {
            x = setPos.group(2);
            y = setPos.group(3);
            continue;
          }
          boolean ok = false;
          for (Pattern p : ALLOWED) {
            Matcher m = p.matcher(u);
            if (m.matches() && m.group(1).equals(var)) {
              body.add(u);
              ok = true;
              break;
            }
          }
          if (!ok) break;
        }
        if (!added) continue;
        if (x != null || y != null) {
          body.add(1, var + ".setPosition(" + (x == null ? "0" : x) + ", "
              + (y == null ? "0" : y) + ");");
        }
        body.add("this.add(" + var + ");");
        out.add(new Candidate(new Promotion(i + 1, j + 1, var, type), body,
            decl == null ? var : null));
        i = j;
      }
    }
    return out;
  }

  private static String apply(String source, Candidate c) {
    String[] lines = source.split("\n", -1);
    Region setup = RegionParser.find(source, "setup");
    int regionFirst = lineOf(source, setup.beginOffset());
    int regionEnd = lineOf(source, setup.endOffset()); // the end marker's line
    int first = c.promotion.firstLine();
    int last = c.promotion.lastLine();
    // the drawing order stays: nothing else is added between the sprite and the region
    int from = last < regionFirst ? last : regionEnd;
    int to = last < regionFirst ? regionFirst - 1 : first - 1;
    for (int k = from + 1; k <= to; k++) {
      if (lines[k - 1].contains("add(")) {
        throw new IllegalStateException("would change the drawing order");
      }
    }
    String indent = lines[regionEnd - 1].replaceAll("\\S.*", "");
    List<String> out = new ArrayList<>();
    for (int k = 1; k <= lines.length; k++) {
      if (k >= first && k <= last) continue;
      if (k == regionEnd && first > regionEnd) {
        for (String l : c.designerLines) out.add(indent + l);
      }
      out.add(lines[k - 1]);
      if (k == regionFirst - 1 && first < regionFirst) {
        for (String l : c.designerLines) out.add(indent + l);
      }
    }
    String result = String.join("\n", out);
    // the field: into the fields region; an existing declaration of it goes
    String modifier = "private";
    if (c.declaration != null) {
      Matcher existing = Pattern.compile("(?m)^\\s*(private|protected|public)?\\s*(?:final\\s+)?"
          + "\\w+\\s+" + Pattern.quote(c.declaration) + "\\s*;\\s*\\n").matcher(result);
      if (existing.find()) {
        modifier = existing.group(1) == null ? "" : existing.group(1);
        result = result.substring(0, existing.start()) + result.substring(existing.end());
      }
    }
    Region fields = RegionParser.find(result, "fields");
    int fieldsAt = fields.endOffset();
    int lineStart = result.lastIndexOf('\n', fieldsAt - 1) + 1;
    String fieldIndent = result.substring(lineStart).replaceAll("(?s)\\S.*", "");
    result = result.substring(0, lineStart) + fieldIndent
        + (modifier.isEmpty() ? "" : modifier + " ") + c.promotion.type() + " "
        + c.promotion.name() + ";\n" + result.substring(lineStart);
    StageDocument.read(result); // throws when the designer could not read it
    return result;
  }

  private static int lineOf(String source, int offset) {
    int line = 1;
    for (int i = 0; i < offset && i < source.length(); i++) {
      if (source.charAt(i) == '\n') line++;
    }
    return line;
  }
}
