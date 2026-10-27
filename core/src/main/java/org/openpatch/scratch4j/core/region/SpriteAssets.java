package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Simple sprite asset calls that the visual editor can round-trip safely. */
public final class SpriteAssets {

  /** {@code SHEET} is {@code addCostumes(prefix, sheet, tileWidth, tileHeight)}. */
  public enum Kind { COSTUME, SOUND, ANIMATION, SHEET }

  /**
   * One asset call. Sheet forms carry their tile size ({@code tileWidth},
   * {@code tileHeight}, and {@code row} for a sheet animation; 0 otherwise).
   * {@code computed} is the path expression as written when the code works the
   * path out ({@code addAnimation("unten", bild, ...)}): then {@code reference}
   * is null and the entry is read-only.
   *
   * <p>Scratch for Java 7 names a sheet animation's frames: {@code addAnimation(name, sheet,
   * width, height, frame, frame, ...)}, counted row by row from 0. {@code sheetFrames} holds them;
   * {@code columns} is true when they step down a column (the step is the sheet's column count)
   * and {@code row} is then the column, otherwise the first frame.
   */
  public record Entry(Kind kind, String name, String reference, int frames, boolean managed,
      int tileWidth, int tileHeight, int row, boolean columns, int cropX, int cropY,
      String computed, List<Integer> sheetFrames) {
    public Entry(Kind kind, String name, String reference, int frames, boolean managed,
        int tileWidth, int tileHeight, int row, boolean columns, int cropX, int cropY,
        String computed) {
      this(kind, name, reference, frames, managed, tileWidth, tileHeight, row, columns, cropX,
          cropY, computed, List.of());
    }

    public Entry(Kind kind, String name, String reference, int frames, boolean managed,
        int tileWidth, int tileHeight, int row, boolean columns, int cropX, int cropY) {
      this(kind, name, reference, frames, managed, tileWidth, tileHeight, row, columns, cropX,
          cropY, null);
    }

    public Entry(Kind kind, String name, String reference, int frames, boolean managed) {
      this(kind, name, reference, frames, managed, 0, 0, 0, false, 0, 0);
    }

    public Entry(Kind kind, String name, String reference, int frames, boolean managed,
        int tileWidth, int tileHeight, int row) {
      this(kind, name, reference, frames, managed, tileWidth, tileHeight, row, false, 0, 0);
    }

    public Entry(Kind kind, String name, String reference, int frames, boolean managed,
        int tileWidth, int tileHeight, int row, boolean columns) {
      this(kind, name, reference, frames, managed, tileWidth, tileHeight, row, columns, 0, 0);
    }

    /** {@code addCostume(name, sheet, x, y, w, h)}: one cell of a sprite sheet. */
    public boolean isSheetCostume() {
      return kind == Kind.COSTUME && tileWidth > 0;
    }

    /** {@code addAnimation(name, sheet, frames, width, height[, row])}. */
    public boolean isSheetAnimation() {
      return kind == Kind.ANIMATION && tileWidth > 0;
    }

    /** The path is worked out by the code, so it is known only when the program runs. */
    public boolean isComputed() {
      return computed != null;
    }
  }

  /** {@code this.addCostumes("prefix", "sheet.png", 32, 32);} */
  private static final Pattern SHEET_COSTUMES = Pattern.compile(
      "(?m)^[ \\t]*this\\.addCostumes\\(\\s*\"([^\"\\r\\n]*)\"\\s*,\\s*\"([^\"\\r\\n]*)\""
          + "\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\);[ \\t]*$");
  /** {@code this.addAnimation("walk", "sheet.png", 4, 32, 32[, 1]);} */
  private static final Pattern SHEET_ANIMATION = Pattern.compile(
      "(?m)^[ \\t]*this\\.addAnimation\\(\\s*\"([^\"\\r\\n]*)\"\\s*,\\s*\"([^\"\\r\\n]*)\""
          + "\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*(\\d+))?\\s*\\);[ \\t]*$");
  private static final Pattern CALL = Pattern.compile(
      "(?m)^[ \\t]*this\\.(addCostume|addSound|addAnimation)\\(\\s*\"([^\"\\r\\n]*)\""
          + "(?:\\s*,\\s*\"([^\"\\r\\n]*)\")?(?:\\s*,\\s*(\\d+))?\\s*\\);[ \\t]*$");
  private static final Pattern HITBOX = Pattern.compile(
      "(?<![\\w.])(?:this\\.)?setHitbox\\(");
  /** Scratch for Java 7: {@code setHitbox(new Polygon(new double[] {xs}, new double[] {ys}))}. */
  private static final Pattern POLYGON_HITBOX = Pattern.compile(
      "\\s*new\\s+(?:org\\.openpatch\\.scratch\\.)?Polygon\\(\\s*new\\s+double\\s*\\[\\s*]\\s*\\{([^}]*)}"
          + "\\s*,\\s*new\\s+double\\s*\\[\\s*]\\s*\\{([^}]*)}\\s*\\)\\s*\\)");
  /** Before 7: {@code setHitbox(x1, y1, x2, y2, ...)}; still read so the editor can rewrite it. */
  private static final Pattern VARARGS_HITBOX = Pattern.compile("([^)]*)\\)");
  private static final Pattern ANY_HITBOX = Pattern.compile(
      "(?:this\\.)?setHitbox\\(");

  private SpriteAssets() {}

  /**
   * Every asset the constructors add, with every library overload (see
   * {@link AssetCalls}); calls inside the setup region are managed (editable).
   */
  public static List<Entry> list(String source) {
    source = DesignerRegions.ensureSprite(source);
    Region region = RegionParser.find(source, "setup");
    List<Entry> entries = new ArrayList<>();
    for (AssetCalls.Call c : AssetCalls.of(source)) {
      Entry entry = entry(c, c.start() >= region.beginOffset()
          && c.end() <= region.endOffset());
      if (entry != null) entries.add(entry);
    }
    return entries;
  }

  /**
   * The entry a call describes: null when the name or a number is not a literal.
   * A path the code works out ({@code bild}) gives a read-only entry with
   * {@code computed} set, so the tab still shows the asset.
   */
  private static Entry entry(AssetCalls.Call c, boolean managed) {
    int n = c.args().size();
    String name = c.string(0);
    if (name == null) return null;
    String path = c.string(1);
    String computed = path == null && n >= 2 && c.args().get(1) == null ? c.code(1) : null;
    boolean hasPath = path != null || computed != null;
    boolean editable = managed && computed == null;
    switch (c.method()) {
      case "addCostume" -> {
        if (n == 6 && hasPath && c.number(2) != null && c.number(3) != null
            && c.number(4) != null && c.number(5) != null) {
          return new Entry(Kind.COSTUME, name, path, 0, editable, c.number(4),
              c.number(5), 0, false, c.number(2), c.number(3), computed);
        }
        return n == 1 || n == 2 && hasPath
            ? new Entry(Kind.COSTUME, name, path, 0, editable, 0, 0, 0, false, 0, 0, computed)
            : null;
      }
      case "addSound" -> {
        return n == 1 || n == 2 && hasPath
            ? new Entry(Kind.SOUND, name, path, 0, editable, 0, 0, 0, false, 0, 0, computed)
            : null;
      }
      case "addCostumes" -> {
        return n == 4 && hasPath && c.number(2) != null && c.number(3) != null
            ? new Entry(Kind.SHEET, name, path, 0, editable, c.number(2), c.number(3), 0, false,
                0, 0, computed)
            : null;
      }
      case "addAnimation" -> {
        if (n == 3 && hasPath) {
          return new Entry(Kind.ANIMATION, name, path, c.number(2) == null ? 0 : c.number(2),
              editable, 0, 0, 0, false, 0, 0, computed);
        }
        // Scratch for Java 7: (name, sheet, width, height, frame, frame, ...)
        if (n >= 5 && hasPath && c.number(2) != null && c.number(3) != null) {
          List<Integer> frames = new ArrayList<>();
          for (int i = 4; i < n; i++) {
            if (c.number(i) == null) return null;
            frames.add(c.number(i));
          }
          boolean down = frames.size() > 1 && frames.get(1) - frames.get(0) > 1;
          return new Entry(Kind.ANIMATION, name, path, frames.size(), editable,
              c.number(2), c.number(3), frames.get(0), down, 0, 0, computed, List.copyOf(frames));
        }
        return null;
      }
      default -> {
        return null;
      }
    }
  }

  /**
   * Adds {@code this.addCostumes(prefix, sheet, tileWidth, tileHeight);}: every
   * tile of the sheet becomes a costume named prefix0, prefix1, ...
   */
  public static String addSheetCostumes(String source, String prefix, String sheet,
      int tileWidth, int tileHeight) {
    source = DesignerRegions.ensureSprite(source);
    checkName(prefix);
    checkReference(sheet);
    if (tileWidth < 1 || tileHeight < 1) {
      throw new IllegalArgumentException("Tiles need a positive width and height");
    }
    if (list(source).stream().anyMatch(e -> e.kind() == Kind.SHEET && e.name().equals(prefix))) {
      throw new IllegalArgumentException("Asset name already exists: " + prefix);
    }
    return append(source, "this.addCostumes(\"" + prefix + "\", \"" + sheet + "\", "
        + tileWidth + ", " + tileHeight + ");");
  }

  /**
   * Adds a sprite-sheet animation: {@code frames} tiles of {@code width x height}
   * from row {@code row} (0 = top), as
   * {@code this.addAnimation(name, sheet, frames, width, height[, row]);}.
   */
  public static String addSheetAnimation(String source, String name, String sheet, int frames,
      int width, int height, int row, int sheetColumns) {
    source = DesignerRegions.ensureSprite(source);
    checkName(name);
    checkReference(sheet);
    if (frames < 1 || width < 1 || height < 1 || row < 0) {
      throw new IllegalArgumentException("Animation needs frames, a tile size and a row");
    }
    if (list(source).stream().anyMatch(e -> e.kind() == Kind.ANIMATION && e.name().equals(name))) {
      throw new IllegalArgumentException("Asset name already exists: " + name);
    }
    // Scratch for Java 7 names the frames, counted row by row from 0
    StringBuilder numbers = new StringBuilder();
    for (int i = 0; i < frames; i++) {
      numbers.append(", ").append(row * Math.max(1, sheetColumns) + i);
    }
    return append(source, "this.addAnimation(\"" + name + "\", \"" + sheet + "\", " + width
        + ", " + height + numbers + ");");
  }

  /**
   * Adds or replaces a file-pattern animation ({@code addAnimation(name, pattern, frames)})
   * — the animation editor's output, so editing the frame count updates the line.
   */
  public static String putAnimation(String source, String name, String pattern, int frames) {
    source = DesignerRegions.ensureSprite(source);
    for (Entry entry : list(source)) {
      if (entry.kind() == Kind.ANIMATION && entry.name().equals(name)) {
        if (!entry.managed()) {
          throw new IllegalArgumentException("This animation is defined in handwritten code");
        }
        source = remove(source, entry);
        break;
      }
    }
    return add(source, Kind.ANIMATION, name, pattern, frames);
  }

  private static void checkName(String name) {
    if (name == null || name.isBlank() || name.contains("\n") || name.contains("\r")
        || name.contains("\"") || name.contains("\\")) {
      throw new IllegalArgumentException("Invalid asset name");
    }
  }

  private static void checkReference(String reference) {
    if (reference == null || reference.isBlank() || reference.contains("\n")
        || reference.contains("\r") || reference.contains("\"") || reference.contains("\\")) {
      throw new IllegalArgumentException("Invalid asset reference");
    }
  }

  private static String append(String source, String call) {
    Region region = RegionParser.find(source, "setup");
    return RegionParser.rewrite(source, "setup", region.body() + region.indent() + call + "\n");
  }

  public static String add(String source, Kind kind, String name, String reference, int frames) {
    source = DesignerRegions.ensureSprite(source);
    if (name == null || name.isBlank() || name.contains("\n") || name.contains("\r")
        || name.contains("\"") || name.contains("\\")) {
      throw new IllegalArgumentException("Invalid asset name");
    }
    if (list(source).stream().anyMatch(e -> e.kind() == kind && e.name().equals(name))) {
      throw new IllegalArgumentException("Asset name already exists: " + name);
    }
    if (reference != null && (reference.isBlank() || reference.contains("\n")
        || reference.contains("\r") || reference.contains("\"")
        || reference.contains("\\"))) {
      throw new IllegalArgumentException("Invalid asset reference");
    }
    if (kind == Kind.ANIMATION && (reference == null || frames < 1
        || !reference.contains("%d"))) {
      throw new IllegalArgumentException("Animation needs a %d frame pattern and frame count");
    }
    String call = switch (kind) {
      case COSTUME -> "this.addCostume(\"" + name + "\""
          + (reference == null ? "" : ", \"" + reference + "\"") + ");";
      case SOUND -> "this.addSound(\"" + name + "\""
          + (reference == null ? "" : ", \"" + reference + "\"") + ");";
      case ANIMATION -> "this.addAnimation(\"" + name + "\", \""
          + reference + "\", " + frames + ");";
      case SHEET -> throw new IllegalArgumentException("Use addSheetCostumes for a sheet");
    };
    Region region = RegionParser.find(source, "setup");
    return RegionParser.rewrite(source, "setup", region.body() + region.indent() + call + "\n");
  }

  /**
   * A managed built-in costume or sound becomes a project file, in place:
   * {@code addCostume("bunny1_stand")} -> {@code addCostume("bunny1_stand",
   * "assets/images/bunny1_stand.png")} (the order of costumes stays).
   */
  public static String useFile(String source, Entry entry, String path) {
    source = DesignerRegions.ensureSprite(source);
    checkReference(path);
    Region region = RegionParser.find(source, "setup");
    for (AssetCalls.Call c : AssetCalls.of(source)) {
      boolean inRegion = c.start() >= region.beginOffset() && c.end() <= region.endOffset();
      Entry found = inRegion ? entry(c, true) : null;
      if (found != null && !found.isComputed() && found.kind() == entry.kind()
          && found.name().equals(entry.name())
          && found.reference() == null && c.args().size() == 1) {
        return source.substring(0, c.start()) + "this." + c.method() + "(\"" + entry.name()
            + "\", \"" + path + "\")" + source.substring(c.end());
      }
    }
    throw new IllegalArgumentException("Only a built-in costume or sound in the setup region");
  }

  /**
   * Replaces a managed entry's call with other statements (the sprite-sheet
   * grid's code), at the same place and indentation; null entry: appended.
   */
  public static String replace(String source, Entry entry, List<String> statements) {
    source = DesignerRegions.ensureSprite(source);
    for (String statement : statements) {
      if (!statement.matches("this\\.add(Costume|Animation)\\(.*\\);")) {
        throw new IllegalArgumentException("Not an asset call: " + statement);
      }
    }
    if (entry == null) {
      for (String statement : statements) source = append(source, statement);
      return source;
    }
    if (!entry.managed()) {
      throw new IllegalArgumentException("This asset is defined in handwritten code");
    }
    Region region = RegionParser.find(source, "setup");
    for (AssetCalls.Call c : AssetCalls.of(source)) {
      boolean inRegion = c.start() >= region.beginOffset() && c.end() <= region.endOffset();
      Entry found = inRegion ? entry(c, true) : null;
      if (found != null && !found.isComputed() && found.kind() == entry.kind()
          && found.name().equals(entry.name())
          && java.util.Objects.equals(found.reference(), entry.reference())) {
        int from = source.lastIndexOf('\n', c.start() - 1) + 1;
        int to = source.indexOf('\n', c.end());
        to = to < 0 ? source.length() : to + 1;
        String indent = source.substring(from, c.start()).replaceAll("\\S.*", "");
        StringBuilder lines = new StringBuilder();
        for (String statement : statements) lines.append(indent).append(statement).append('\n');
        return source.substring(0, from) + lines + source.substring(to);
      }
    }
    throw new IllegalArgumentException("Asset no longer exists: " + entry.name());
  }

  /** Only managed calls may be removed; handwritten calls remain untouched. */
  public static String remove(String source, Entry entry) {
    source = DesignerRegions.ensureSprite(source);
    if (!entry.managed()) {
      throw new IllegalArgumentException("This asset is defined in handwritten code");
    }
    Region region = RegionParser.find(source, "setup");
    for (AssetCalls.Call c : AssetCalls.of(source)) {
      boolean inRegion = c.start() >= region.beginOffset() && c.end() <= region.endOffset();
      Entry found = inRegion ? entry(c, true) : null;
      if (found != null && !found.isComputed() && found.kind() == entry.kind()
          && found.name().equals(entry.name())
          && java.util.Objects.equals(found.reference(), entry.reference())) {
        // the whole line(s) of the call, with the statement's semicolon
        int from = source.lastIndexOf('\n', c.start() - 1) + 1;
        int to = source.indexOf('\n', c.end());
        to = to < 0 ? source.length() : to + 1;
        return source.substring(0, from) + source.substring(to);
      }
    }
    throw new IllegalArgumentException("Asset no longer exists: " + entry.name());
  }

  /** Literal x/y pairs, or an empty array when none is present. */
  public static double[] hitbox(String source) {
    source = DesignerRegions.ensureSprite(source);
    Matcher matcher = HITBOX.matcher(source);
    if (!matcher.find()) {
      return new double[0];
    }
    double[] points = literalHitbox(source, matcher.end());
    if (points == null) {
      throw new IllegalArgumentException("The hitbox is not a literal polygon");
    }
    return points;
  }

  /**
   * The x/y pairs of the first {@code setHitbox} call with a literal polygon in any
   * class source, or null when there is none (no call, an ellipse, computed numbers).
   */
  public static double[] literalHitbox(String source) {
    Matcher matcher = HITBOX.matcher(source);
    return matcher.find() ? literalHitbox(source, matcher.end()) : null;
  }

  /** The polygon whose arguments start at {@code from} (just after {@code setHitbox(}). */
  private static double[] literalHitbox(String source, int from) {
    try {
      Matcher polygon = POLYGON_HITBOX.matcher(source).region(from, source.length());
      if (polygon.lookingAt()) {
        double[] xs = numbers(polygon.group(1));
        double[] ys = numbers(polygon.group(2));
        if (xs.length < 3 || xs.length != ys.length) {
          return null;
        }
        double[] points = new double[2 * xs.length];
        for (int i = 0; i < xs.length; i++) {
          points[2 * i] = xs[i];
          points[2 * i + 1] = ys[i];
        }
        return points;
      }
      Matcher varargs = VARARGS_HITBOX.matcher(source).region(from, source.length());
      if (varargs.lookingAt()) {
        double[] points = numbers(varargs.group(1));
        return points.length >= 6 && points.length % 2 == 0 ? points : null;
      }
    } catch (NumberFormatException e) {
      // a computed coordinate
    }
    return null;
  }

  private static double[] numbers(String list) {
    String[] tokens = list.split(",");
    double[] numbers = new double[tokens.length];
    for (int i = 0; i < tokens.length; i++) {
      numbers[i] = Double.parseDouble(tokens[i].trim());
    }
    return numbers;
  }

  public static boolean hasUnmanagedHitbox(String source) {
    source = DesignerRegions.ensureSprite(source);
    Region region = RegionParser.find(source, "setup");
    Matcher matcher = ANY_HITBOX.matcher(source);
    while (matcher.find()) {
      if (matcher.start() < region.beginOffset() || matcher.end() > region.endOffset()
          || !matcher.group().startsWith("this.")) {
        return true;
      }
    }
    return false;
  }
}
