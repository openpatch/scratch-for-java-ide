package org.openpatch.scratch4j.core.region;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

/**
 * One stage class as the designer sees it: the {@code fields} and
 * {@code setup} regions hold the model; everything else in the file is
 * untouchable. Read and write round-trip losslessly for canonical bodies;
 * bodies outside the designer's subset make the document
 * {@link #isDesignerEditable() not designer-editable} (read-only) instead of
 * destroying user code.
 */
public final class StageDocument {

  private static final Pattern SUPER_SIZE =
      Pattern.compile("super\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

  private final String source;
  private final StageModel model;

  public record Dimensions(int width, int height) {}

  private StageDocument(String source, StageModel model) {
    this.source = source;
    this.model = model;
  }

  /** Parses a stage class source; throws on regions outside the subset. */
  public static StageDocument read(String source) {
    StageModel model = StageModel.create();
    declaredSize(source).ifPresent(size -> model.size(size.width(), size.height()));
    List<Region> regions = RegionParser.parse(source);
    Region fields = find(regions, "fields");
    Region setup = find(regions, "setup");
    if (fields != null) {
      RegionStatements.parseFields(fields.body(), model);
    }
    RegionStatements.parseSetup(setup == null ? "" : setup.body(), model);
    return new StageDocument(source, model);
  }

  /** Reads a literal {@code super(width, height)} even if designer regions are unsupported. */
  public static Optional<Dimensions> declaredSize(String source) {
    Matcher size = SUPER_SIZE.matcher(source);
    return size.find()
        ? Optional.of(new Dimensions(Integer.parseInt(size.group(1)),
            Integer.parseInt(size.group(2))))
        : Optional.empty();
  }

  private static Region find(List<Region> regions, String id) {
    return regions.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
  }

  /** True when the regions parse into the designer subset (this read succeeded). */
  public static boolean isDesignerEditable(String source) {
    try {
      read(source);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** Why the designer cannot read a stage's regions: the line (1-based, in the file) and why. */
  public record RegionIssue(int line, String statement, String message) {}

  private static final Pattern REGION_LINE =
      Pattern.compile("^(fields|setup) line (\\d+)(?::| is)\\s*(.*)$");
  private static final Pattern UNADDED =
      Pattern.compile("^sprite (\\w+) is declared but not instantiated and added$");
  private static final Pattern DUPLICATE = Pattern.compile("^duplicate sprite name: (\\w+)$");

  /** The line that makes the designer read-only (null: the regions are readable or absent). */
  public static RegionIssue regionIssue(String source) {
    try {
      read(source);
      return null;
    } catch (RegionStatements.UnsupportedRegionException e) {
      String message = e.getMessage() == null ? "" : e.getMessage();
      String[] lines = source.split("\n", -1);
      Matcher m = REGION_LINE.matcher(message);
      if (m.matches()) {
        Region region = RegionParser.find(source, m.group(1));
        int first = lineOf(source, region.beginOffset());
        int line = first + Integer.parseInt(m.group(2)) - 1;
        String statement = line >= 1 && line <= lines.length ? lines[line - 1].strip() : "";
        return new RegionIssue(line, statement, m.group(3));
      }
      Matcher unadded = UNADDED.matcher(message);
      Matcher duplicate = DUPLICATE.matcher(message);
      String name = unadded.matches() ? unadded.group(1)
          : duplicate.matches() ? duplicate.group(1) : null;
      if (name != null && RegionParser.has(source, "fields")) {
        // the field declaration of that sprite (the last one for a duplicate)
        Region fields = RegionParser.find(source, "fields");
        int first = lineOf(source, fields.beginOffset());
        String[] body = source.substring(fields.beginOffset(), fields.endOffset())
            .split("\n", -1);
        int found = -1;
        for (int i = 0; i < body.length; i++) {
          if (body[i].matches("\\s*\\w+\\s+" + Pattern.quote(name) + "\\s*;\\s*")) {
            found = first + i;
          }
        }
        if (found > 0) return new RegionIssue(found, lines[found - 1].strip(), message);
      }
      return new RegionIssue(0, "", message);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Line (1-based) of an offset. */
  private static int lineOf(String source, int offset) {
    int line = 1;
    for (int i = 0; i < offset && i < source.length(); i++) {
      if (source.charAt(i) == '\n') line++;
    }
    return line;
  }

  /**
   * Moves one statement (1-based {@code line}) of a designer region to just
   * below the region, where it stays the student's own code; the designer can
   * read the region again (when nothing else in it is foreign).
   */
  public static String moveOutOfRegion(String source, int line) {
    String[] lines = source.split("\n", -1);
    if (line < 1 || line > lines.length) {
      throw new IllegalArgumentException("No line " + line);
    }
    for (Region region : RegionParser.parse(source)) {
      int first = lineOf(source, region.beginOffset());
      int last = lineOf(source, region.endOffset()) - 1;
      if (line < first || line > last) continue;
      // the end marker's line comes right after the body
      int endMarker = last + 1;
      java.util.List<String> out = new java.util.ArrayList<>(java.util.Arrays.asList(lines));
      String moved = out.remove(line - 1);
      endMarker -= 1;
      out.add(endMarker, moved);
      return String.join("\n", out);
    }
    throw new IllegalArgumentException("Line " + line + " is not in a designer region");
  }

  public StageModel model() {
    return model;
  }

  /** Rename a managed sprite field when handwritten stage code has no references to it. */
  public void renameInstance(String oldName, String newName) {
    if (!newName.matches("[A-Za-z_][A-Za-z0-9_]*")
        || SourceVersion.isKeyword(newName)) {
      throw new IllegalArgumentException("Not a valid Java field name: " + newName);
    }
    SpriteRef old = model.sprites().byName(oldName);
    if (old == null || model.sprites().byName(newName) != null) {
      throw new IllegalArgumentException("Unknown or duplicate sprite name: " + newName);
    }
    StringBuilder outside = new StringBuilder(source);
    for (Region region : RegionParser.parse(source)) {
      if (region.id().equals("fields") || region.id().equals("setup")) {
        for (int i = region.beginOffset(); i < region.endOffset(); i++) {
          outside.setCharAt(i, ' ');
        }
      }
    }
    if (Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(oldName)
        + "(?![A-Za-z0-9_$])").matcher(outside).find()) {
      throw new IllegalArgumentException(
          "Hand-written stage code refers to " + oldName + "; rename it in code first");
    }
    model.sprites().set(model.sprites().indexOf(old), old.copyAs(newName));
  }

  public String source() {
    return source;
  }

  /**
   * Serializes the model back into the regions (and keeps {@code super(w, h)}
   * in sync with the model's size — that one line is the stage's size
   * declaration, which the designer owns). The fields region must exist when
   * the model has sprites (the templates create it). Returns the new source
   * text; nothing is written to disk here.
   */
  public String write(StageModel updated) {
    // a stage written by hand gets its regions with the first designer edit
    String result = DesignerRegions.ensureStage(source);
    if (!updated.sprites().isEmpty()) {
      if (!RegionParser.has(result, "fields")) {
        throw new RegionStatements.UnsupportedRegionException(
            "stage class has no fields region for its sprites");
      }
      Region fields = RegionParser.find(result, "fields");
      result = RegionParser.rewrite(result, "fields",
          RegionStatements.generateFields(updated, fields.indent()));
    } else if (RegionParser.has(result, "fields")) {
      Region fields = RegionParser.find(result, "fields");
      result = RegionParser.rewrite(result, "fields",
          RegionStatements.generateFields(updated, fields.indent()));
    }
    Region setup = RegionParser.find(result, "setup");
    result = RegionParser.rewrite(result, "setup",
        RegionStatements.generateSetup(updated, setup.indent()));
    Matcher size = SUPER_SIZE.matcher(result);
    if (size.find()) {
      result = size.replaceFirst("super(" + updated.width() + ", " + updated.height() + ")");
    }
    boolean texts = updated.sprites().stream().anyMatch(SpriteRef::isText);
    boolean styles = updated.sprites().stream()
        .anyMatch(r -> r.isText() && r.textStyle() != null && r.textStyle().startsWith("TextStyle."));
    if (texts) {
      result = ensureImport(result, "Text");
    }
    if (styles) {
      result = ensureImport(result, "TextStyle");
    }
    if (updated.sprites().stream().anyMatch(r -> r.rotationStyle() != null)) {
      result = ensureImport(result, "RotationStyle");
    }
    return result;
  }

  /**
   * Adds {@code import org.openpatch.scratch.<simpleName>;} after the last
   * import (or at the top) unless the class or the package wildcard is
   * already imported. Generated {@code Text} fields need it to compile.
   */
  static String ensureImport(String source, String simpleName) {
    return org.openpatch.scratch4j.core.project.JavaImports.ensure(source,
        "org.openpatch.scratch." + simpleName);
  }

  /** Convenience: write(this model()). */
  public String write() {
    return write(model);
  }
}
