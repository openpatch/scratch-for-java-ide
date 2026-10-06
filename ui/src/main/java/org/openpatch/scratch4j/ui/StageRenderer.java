package org.openpatch.scratch4j.ui;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.SpriteRef;
import org.openpatch.scratch4j.core.region.StageDocument;
import org.openpatch.scratch4j.core.region.StageModel;
import org.openpatch.scratch.RotationStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws a {@link StageModel} the way the library will: first backdrop
 * (stretched to the stage, as Scratch does), sprites in add order at their
 * Scratch coordinates (origin centre, y up), size %, direction. A sprite
 * class's own constructor defaults (first costume, setSize, setDirection)
 * apply when the stage does not override them, like at run time.
 */
final class StageRenderer {

  /**
   * How a sprite instance (or a {@code Text}) looks: resolved costume, effective
   * size/direction, the drawn pixel size (UISprite {@code setWidth}/{@code setHeight}
   * win over size %), the nine-slice insets in drawn pixels, and — for texts —
   * the wrapped lines. {@code offsetX/offsetY} is the centre of the drawn box
   * relative to the object's position, in stage pixels with y pointing down
   * (0 for sprites; a plain text starts at its x, a box hangs above its y).
   */
  record Look(Image image, double size, double direction, RotationStyle rotationStyle,
      double drawWidth, double drawHeight, double offsetX, double offsetY,
      int[] slice, int[] originalSlice, java.util.List<String> lines, String textStyle) {

    /** A plain sprite look at the costume's size % (48 px placeholder without an image). */
    Look(Image image, double size, double direction, RotationStyle rotationStyle) {
      this(image, size, direction, rotationStyle,
          (image == null ? 48 : image.getWidth()) * size / 100.0,
          (image == null ? 48 : image.getHeight()) * size / 100.0, 0, 0, null, null, null, null);
    }

    double width() {
      return drawWidth;
    }

    double height() {
      return drawHeight;
    }

    boolean isText() {
      return lines != null;
    }

    double rotationAngle() {
      return !isText() && rotationStyle == RotationStyle.ALL_AROUND ? direction - 90 : 0;
    }

    boolean mirrored() {
      double degrees = direction - 90;
      return !isText() && rotationStyle == RotationStyle.LEFT_RIGHT
          && !(degrees > -90 && degrees < 90);
    }
  }

  /** The library's text metrics: 14 px font, 4 px extra leading, 8 px frame padding. */
  static final double TEXT_SIZE = 14;
  static final double TEXT_LEADING = TEXT_SIZE + 4;
  static final double TEXT_PADDING = 8;
  private static final javafx.scene.text.Font TEXT_FONT =
      javafx.scene.text.Font.font("SansSerif", TEXT_SIZE);

  /** Defaults a sprite class sets on itself in its constructor. */
  private record ClassDefaults(long modified, String costume, Double size, Double direction,
      RotationStyle style, Map<String, String> costumes, double[] hitbox,
      double[] rotationCenter, Double width, Double height, int[] nineSlice) {}

  private static final Pattern COSTUME = Pattern.compile("addCostume\\(\\s*\"([^\"]+)\"");
  private static final Pattern COSTUME_WITH_PATH =
      Pattern.compile("addCostume\\(\\s*\"[^\"]+\"\\s*,\\s*\"([^\"]+)\"");
  private static final Pattern SIZE =
      Pattern.compile("(?<![\\w.])(?:this\\.)?setSize\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)");
  private static final Pattern DIRECTION =
      Pattern.compile("(?<![\\w.])(?:this\\.)?setDirection\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)");
  private static final Pattern ROTATION_STYLE = Pattern.compile(
      "setRotationStyle\\(\\s*(?:(?:org\\.openpatch\\.scratch\\.)?RotationStyle\\.)?(ALL_AROUND|LEFT_RIGHT|DONT)\\s*\\)");
  private static final Pattern COSTUME_ENTRY = Pattern.compile(
      "addCostume\\(\\s*\"([^\"]+)\"(?:\\s*,\\s*\"([^\"]+)\")?");
  private static final Pattern WIDTH =
      Pattern.compile("(?<![\\w.])(?:this\\.)?setWidth\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)");
  private static final Pattern HEIGHT =
      Pattern.compile("(?<![\\w.])(?:this\\.)?setHeight\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)");
  private static final Pattern NINE_SLICE = Pattern.compile(
      "setNineSlice\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
  private static final Pattern HITBOX = Pattern.compile("setHitbox\\(([^)]*)\\)");
  private static final Pattern ROTATION_CENTER = Pattern.compile(
      "setRotationCenter\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)");

  private final ScratchProject project;
  private final Map<String, ClassDefaults> defaults = new ConcurrentHashMap<>();

  StageRenderer(ScratchProject project) {
    this.project = project;
  }

  Look look(SpriteRef ref) {
    if (ref.isText()) {
      return textLook(ref);
    }
    ClassDefaults d = defaultsOf(ref.type());
    String costume = ref.currentCostume() != null
        ? d.costumes().getOrDefault(ref.currentCostume(), ref.currentCostume())
        : ref.costume() != null ? ref.costume() : d.costume();
    double size = ref.hasSize() ? ref.size() : d.size() != null ? d.size() : 100;
    double direction = ref.hasDirection() ? ref.direction()
        : d.direction() != null ? d.direction() : 90;
    RotationStyle style = ref.rotationStyle() == null ? d.style()
        : RotationStyle.valueOf(ref.rotationStyle()
            .substring(ref.rotationStyle().lastIndexOf('.') + 1));
    Image image = CostumeView.costume(costume, project.root());
    double imageWidth = image == null ? 48 : image.getWidth();
    double imageHeight = image == null ? 48 : image.getHeight();
    // like the library: setSize rescales the costume, a later setWidth/setHeight
    // sets its pixel size; the stage's own calls come after the class's
    double drawWidth = ref.hasWidth() ? ref.width()
        : !ref.hasSize() && d.width() != null ? d.width() : imageWidth * size / 100.0;
    double drawHeight = ref.hasHeight() ? ref.height()
        : !ref.hasSize() && d.height() != null ? d.height() : imageHeight * size / 100.0;
    int[] slice = null;
    if (d.nineSlice() != null) {
      slice = new int[4];
      for (int i = 0; i < 4; i++) {
        slice[i] = (int) Math.round(d.nineSlice()[i] * size / 100.0);
      }
    }
    return new Look(image, size, direction, style, drawWidth, drawHeight, 0, 0,
        slice, d.nineSlice(), null, null);
  }

  // --- Tiled map backdrop and camera (preview of new TiledMap(..., this) / getCamera()) -----

  private static final Pattern CAMERA = Pattern.compile(
      "getCamera\\(\\)\\.setPosition\\(\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\)");
  private org.openpatch.scratch4j.core.assets.TiledMapReader.TiledMap map;
  private final Map<Path, Image> tilesets = new ConcurrentHashMap<>();
  private double cameraX;
  private double cameraY;
  /** Whether the stage sets its camera to literal numbers (else the preview chooses). */
  private boolean literalCamera;
  private boolean showMap = true;

  private List<Path> mapChoices = List.of();
  private Path chosenMap;

  /**
   * Reads the stage's map and camera position (absent: no map, camera at 0, 0).
   * A computed map path offers every project map; the designer lets the user
   * pick which one to preview.
   */
  void scan(String stageSource) {
    map = null;
    cameraX = 0;
    cameraY = 0;
    mapChoices = org.openpatch.scratch4j.core.tiled.MapReferences.forStage(project.root(),
        stageSource);
    if (chosenMap == null || !mapChoices.contains(chosenMap)) {
      chosenMap = mapChoices.isEmpty() ? null : mapChoices.get(0);
    }
    loadMap();
    Matcher camera = CAMERA.matcher(stageSource);
    literalCamera = false;
    if (camera.find()) {
      literalCamera = true;
      cameraX = Double.parseDouble(camera.group(1));
      cameraY = Double.parseDouble(camera.group(2));
    }
  }

  private void loadMap() {
    map = null;
    mapObjects = List.of();
    if (chosenMap != null) {
      try {
        map = org.openpatch.scratch4j.core.assets.TiledMapReader.read(chosenMap);
        // the object layers with ids and shapes (spawn points, walls, items...)
        List<org.openpatch.scratch4j.core.tiled.TmxDocument.MapObject> objects =
            new java.util.ArrayList<>();
        var doc = org.openpatch.scratch4j.core.tiled.TmxDocument.open(chosenMap);
        for (var layer : doc.layers) {
          if (layer instanceof org.openpatch.scratch4j.core.tiled.TmxDocument.ObjectLayer o
              && layer.visible) {
            objects.addAll(o.objects);
          }
        }
        mapObjects = objects;
      } catch (IOException | RuntimeException e) {
        map = null;
      }
    }
  }

  private List<org.openpatch.scratch4j.core.tiled.TmxDocument.MapObject> mapObjects = List.of();
  private boolean showMapObjects = true;

  void showMapObjects(boolean show) {
    showMapObjects = show;
  }

  /** The map's objects (tests, hit checks). */
  List<org.openpatch.scratch4j.core.tiled.TmxDocument.MapObject> mapObjects() {
    return mapObjects;
  }

  /**
   * The Tiled objects in their places (tile (0,0) at the world origin, y
   * down in the map): outlines with the name or type, so spawn points, walls
   * and items show without running the game.
   */
  private void drawMapObjects(GraphicsContext g, double w, double h, double scale) {
    if (map == null || !showMap || !showMapObjects) return;
    g.save();
    g.setLineWidth(1.5);
    g.setFont(javafx.scene.text.Font.font("System", 11));
    for (var o : mapObjects) {
      double[] r = objectBounds(o);
      double sx = w / 2 + (r[0] - cameraX) * scale;
      double sy = h / 2 + (r[1] + cameraY) * scale;
      double sw = Math.max(0, r[2] - r[0]) * scale;
      double sh = Math.max(0, r[3] - r[1]) * scale;
      Color color = objectColor(o.type);
      g.setStroke(color);
      g.setFill(color.deriveColor(0, 1, 1, 0.18));
      switch (o.shape) {
        case POINT -> {
          g.setFill(color);
          g.fillOval(sx - 5, sy - 5, 10, 10);
          g.setStroke(Color.WHITE);
          g.strokeOval(sx - 5, sy - 5, 10, 10);
        }
        case ELLIPSE -> {
          g.fillOval(sx, sy, sw, sh);
          g.strokeOval(sx, sy, sw, sh);
        }
        case POLYGON, POLYLINE -> {
          double[] xs = new double[o.points.size()];
          double[] ys = new double[o.points.size()];
          for (int i = 0; i < xs.length; i++) {
            xs[i] = w / 2 + (o.x + o.points.get(i)[0] - cameraX) * scale;
            ys[i] = h / 2 + (o.y + o.points.get(i)[1] + cameraY) * scale;
          }
          if (o.shape == org.openpatch.scratch4j.core.tiled.TmxDocument.Shape.POLYGON) {
            g.fillPolygon(xs, ys, xs.length);
            g.strokePolygon(xs, ys, xs.length);
          } else {
            g.strokePolyline(xs, ys, xs.length);
          }
        }
        default -> {
          g.fillRect(sx, sy, sw, sh);
          g.strokeRect(sx, sy, sw, sh);
        }
      }
      String label = !o.name.isEmpty() ? o.name : o.type;
      if (!label.isEmpty()) {
        double tw = labelWidth(label);
        g.setFill(Color.rgb(255, 255, 255, 0.85));
        g.fillRect(sx, sy - 15, tw + 6, 14);
        g.setFill(color.darker());
        g.fillText(label, sx + 3, sy - 4);
      }
    }
    g.restore();
  }

  /** [minX, minY, maxX, maxY] of an object in map pixels (y down). */
  static double[] objectBounds(org.openpatch.scratch4j.core.tiled.TmxDocument.MapObject o) {
    switch (o.shape) {
      case POLYGON, POLYLINE -> {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] p : o.points) {
          minX = Math.min(minX, o.x + p[0]);
          minY = Math.min(minY, o.y + p[1]);
          maxX = Math.max(maxX, o.x + p[0]);
          maxY = Math.max(maxY, o.y + p[1]);
        }
        return new double[] {minX, minY, maxX, maxY};
      }
      case TILE -> {
        return new double[] {o.x, o.y - o.height, o.x + o.width, o.y};
      }
      default -> {
        return new double[] {o.x, o.y, o.x + o.width, o.y + o.height};
      }
    }
  }

  /** The map object under a canvas point (map pixels from the camera), or null. */
  org.openpatch.scratch4j.core.tiled.TmxDocument.MapObject mapObjectAt(double canvasX,
      double canvasY, double w, double h, double scale) {
    if (map == null || !showMap || !showMapObjects) return null;
    double mx = (canvasX - w / 2) / scale + cameraX;
    double my = (canvasY - h / 2) / scale - cameraY;
    for (int i = mapObjects.size() - 1; i >= 0; i--) {
      var o = mapObjects.get(i);
      double[] r = objectBounds(o);
      double pad = 6 / scale;
      if (mx >= r[0] - pad && mx <= r[2] + pad && my >= r[1] - pad && my <= r[3] + pad) {
        return o;
      }
    }
    return null;
  }

  private static Color objectColor(String type) {
    Color[] palette = {Color.web("#d6336c"), Color.web("#1c7ed6"), Color.web("#2b8a3e"),
        Color.web("#e8590c"), Color.web("#7048e8"), Color.web("#0c8599")};
    int i = type == null || type.isEmpty() ? 4 : Math.floorMod(type.hashCode(), palette.length);
    return palette[i];
  }

  private static double labelWidth(String text) {
    javafx.scene.text.Text t = new javafx.scene.text.Text(text);
    t.setFont(javafx.scene.text.Font.font("System", 11));
    return t.getLayoutBounds().getWidth();
  }

  /** The maps the stage may show (more than one when its map path is computed). */
  List<Path> mapChoices() {
    return mapChoices;
  }

  Path chosenMap() {
    return chosenMap;
  }

  void chooseMap(Path map) {
    chosenMap = map;
    loadMap();
  }

  /**
   * Without a literal camera position, a stage with a map is previewed with the
   * map's top left corner in the screen's top left corner (TiledMap puts tile
   * (0, 0) at the stage centre; the game usually moves the camera at runtime).
   */
  void defaultCamera(double stageWidth, double stageHeight) {
    if (!literalCamera && map != null) {
      cameraX = stageWidth / 2;
      cameraY = -stageHeight / 2;
    } else if (!literalCamera) {
      cameraX = 0;
      cameraY = 0;
    }
  }

  /** The size in the project's window class ({@code super(640, 360, ...)}), if any. */
  java.util.Optional<StageDocument.Dimensions> windowSize() {
    try {
      for (String window : project.windowClasses()) {
        Path file = project.sourceOf(window);
        if (file == null) continue;
        Matcher m = Pattern.compile("super\\(\\s*(\\d+)\\s*,\\s*(\\d+)")
            .matcher(Files.readString(file, StandardCharsets.UTF_8));
        if (m.find()) {
          return java.util.Optional.of(new StageDocument.Dimensions(
              Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
      }
    } catch (IOException e) {
      // the default size
    }
    return java.util.Optional.empty();
  }

  boolean hasMap() {
    return map != null;
  }

  void showMap(boolean show) {
    showMap = show;
  }

  /** The camera position the stage starts with; the designer shows the world through it. */
  /** The camera a sprite is seen through: none for UI sprites (they ignore the camera). */
  double cameraX(SpriteRef ref) {
    return isScreenFixed(ref.type()) ? 0 : cameraX;
  }

  double cameraY(SpriteRef ref) {
    return isScreenFixed(ref.type()) ? 0 : cameraY;
  }

  /**
   * Whether a sprite class is drawn on the UI layer: a UISprite, or a class
   * (or one of its project superclasses) that calls {@code setUI(true)}.
   */
  boolean isScreenFixed(String className) {
    String name = className;
    for (int depth = 0; depth < 10 && name != null; depth++) {
      if (name.equals("UISprite")) return true;
      Path file = project.root().resolve(name + ".java");
      if (!Files.isRegularFile(file)) return false;
      try {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (source.matches("(?s).*\\bsetUI\\(\\s*true\\s*\\).*")) return true;
        Matcher m = Pattern.compile("\\bclass\\s+" + Pattern.quote(name)
            + "\\s+extends\\s+(\\w+)").matcher(source);
        name = m.find() ? m.group(1) : null;
      } catch (IOException e) {
        return false;
      }
    }
    return false;
  }

  double cameraX() {
    return cameraX;
  }

  double cameraY() {
    return cameraY;
  }

  /** Tile (0,0) sits at the world origin; the map extends right and down (as TiledMap stamps it). */
  private void drawMap(GraphicsContext g, double w, double h, double scale) {
    var m = map;
    if (m == null || !showMap) {
      return;
    }
    g.setImageSmoothing(false);
    for (var layer : m.layers()) {
      if (!layer.visible()) {
        continue;
      }
      g.setGlobalAlpha(layer.opacity());
      for (int i = 0; i < layer.gids().length; i++) {
        long gid = layer.gids()[i];
        if (org.openpatch.scratch4j.core.assets.TiledMapReader.id(gid) == 0) {
          continue;
        }
        var tileset = m.tilesetOf(gid);
        if (tileset == null || tileset.image() == null) {
          continue;
        }
        Image image = tilesets.computeIfAbsent(tileset.image(), path -> {
          try (var in = Files.newInputStream(path)) {
            return new Image(in);
          } catch (IOException e) {
            return null;
          }
        });
        if (image == null) {
          continue;
        }
        long local = org.openpatch.scratch4j.core.assets.TiledMapReader.id(gid)
            - tileset.firstGid();
        int columns = Math.max(1, tileset.columns());
        double worldX = (i % layer.width()) * m.tileWidth();
        double worldTop = -(i / layer.width()) * (double) m.tileHeight();
        double sx = w / 2 + (worldX - cameraX) * scale;
        double sy = h / 2 - (worldTop - cameraY) * scale;
        g.drawImage(image, (local % columns) * tileset.tileWidth(),
            (local / columns) * tileset.tileHeight(), tileset.tileWidth(), tileset.tileHeight(),
            sx, sy, m.tileWidth() * scale, m.tileHeight() * scale);
      }
    }
    g.setGlobalAlpha(1);
  }

  /** Stage width used for a BOX text without a wrap width (the library wraps at the buffer). */
  private double stageWidth = 480;

  private Look textLook(SpriteRef ref) {
    String style = ref.textStyle() == null ? "PLAIN"
        : ref.textStyle().substring(ref.textStyle().lastIndexOf('.') + 1);
    String words = unescape(ref.text() == null ? "" : ref.text());
    java.util.List<String> lines;
    double width;
    double height;
    double offsetX;
    double offsetY;
    if ("PLAIN".equals(style)) {
      lines = ref.textWidth() > 0 ? wrap(words, ref.textWidth())
          : java.util.List.of(words.split("\n", -1));
      width = Math.max(ref.textWidth(), longest(lines));
      height = TEXT_LEADING * lines.size();
      offsetX = width / 2;
      offsetY = 0;
    } else {
      double wrapWidth = ref.textWidth() > 0 ? ref.textWidth() : stageWidth - 16;
      lines = wrap(words, wrapWidth - 2 * TEXT_PADDING);
      width = longest(lines) + 2 * TEXT_PADDING;
      height = TEXT_LEADING * lines.size() + 2 * TEXT_PADDING;
      offsetX = width / 2;
      offsetY = -height / 2;
    }
    return new Look(null, 100, 90, RotationStyle.DONT, Math.max(width, 8), height,
        offsetX, offsetY, null, null, lines, style);
  }

  static String unescape(String literal) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < literal.length(); i++) {
      char c = literal.charAt(i);
      if (c == '\\' && i + 1 < literal.length()) {
        char next = literal.charAt(++i);
        sb.append(switch (next) {
          case 'n' -> '\n';
          case 't' -> '\t';
          default -> next;
        });
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  static String escape(String words) {
    return words.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        .replace("\t", "\\t");
  }

  private static double textWidth(String line) {
    javafx.scene.text.Text measure = new javafx.scene.text.Text(line);
    measure.setFont(TEXT_FONT);
    return measure.getLayoutBounds().getWidth();
  }

  private static double longest(java.util.List<String> lines) {
    double max = 0;
    for (String line : lines) {
      max = Math.max(max, textWidth(line));
    }
    return max;
  }

  /** Word wrap like the library: break between words, keep explicit line breaks. */
  static java.util.List<String> wrap(String words, double maxWidth) {
    java.util.List<String> lines = new java.util.ArrayList<>();
    for (String paragraph : words.split("\n", -1)) {
      StringBuilder line = new StringBuilder();
      for (String word : paragraph.split(" ")) {
        String candidate = line.isEmpty() ? word : line + " " + word;
        if (!line.isEmpty() && textWidth(candidate) > maxWidth) {
          lines.add(line.toString());
          line = new StringBuilder(word);
        } else {
          line = new StringBuilder(candidate);
        }
      }
      lines.add(line.toString());
    }
    return lines;
  }

  /** Whether the class extends {@code UISprite} (it is sized in pixels). */
  boolean isUiSprite(String className) {
    if ("UISprite".equals(className)) {
      return true;
    }
    try {
      return Pattern.compile("\\bclass\\s+" + Pattern.quote(className)
          + "\\s+extends\\s+UISprite\\b").matcher(Files.readString(
              project.root().resolve(className + ".java"), StandardCharsets.UTF_8)).find();
    } catch (IOException e) {
      return false;
    }
  }

  /** The costume a sprite class shows first (for sprite tiles). */
  Image classCostume(String className) {
    return CostumeView.costume(defaultsOf(className).costume(), project.root());
  }

  java.util.List<String> costumeNames(String className) {
    return java.util.List.copyOf(defaultsOf(className).costumes().keySet());
  }

  private ClassDefaults defaultsOf(String className) {
    Path file = project.root().resolve(className + ".java");
    long modified;
    try {
      modified = Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return new ClassDefaults(0, null, null, null, RotationStyle.ALL_AROUND,
          Map.of(), null, null, null, null, null);
    }
    ClassDefaults cached = defaults.get(className);
    if (cached != null && cached.modified() == modified) {
      return cached;
    }
    String costume = null;
    Double size = null;
    Double direction = null;
    RotationStyle style = RotationStyle.ALL_AROUND;
    Map<String, String> costumes = new java.util.LinkedHashMap<>();
    double[] hitbox = null;
    double[] rotationCenter = null;
    Double width = null;
    Double height = null;
    int[] nineSlice = null;
    try {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      // the costume the constructor adds first, also through animations, sprite sheets,
      // super(...) and constructor parameters (new Racer("bee", ...))
      costume = org.openpatch.scratch4j.core.region.SpriteLook.of(project.root(), className);
      if (costume == null) {
        Matcher withPath = COSTUME_WITH_PATH.matcher(source);
        Matcher plain = COSTUME.matcher(source);
        if (withPath.find() && plain.find() && withPath.start() == plain.start()) {
          costume = withPath.group(1);
        } else if (plain.find(0)) {
          costume = plain.group(1);
        }
      }
      Matcher s = SIZE.matcher(source);
      if (s.find()) {
        size = Double.parseDouble(s.group(1));
      }
      Matcher dir = DIRECTION.matcher(source);
      if (dir.find()) {
        direction = Double.parseDouble(dir.group(1));
      }
      Matcher rotation = ROTATION_STYLE.matcher(source);
      if (rotation.find()) {
        style = RotationStyle.valueOf(rotation.group(1));
      }
      Matcher entries = COSTUME_ENTRY.matcher(source);
      while (entries.find()) {
        costumes.put(entries.group(1), entries.group(2) == null
            ? entries.group(1) : entries.group(2));
      }
      Matcher hitboxCall = HITBOX.matcher(source);
      if (hitboxCall.find()) {
        String[] coordinates = hitboxCall.group(1).split(",");
        if (coordinates.length >= 6 && coordinates.length % 2 == 0) {
          double[] points = new double[coordinates.length];
          for (int i = 0; i < coordinates.length; i++) {
            points[i] = Double.parseDouble(coordinates[i].trim());
          }
          hitbox = points;
        }
      }
      Matcher w = WIDTH.matcher(source);
      if (w.find()) {
        width = Double.parseDouble(w.group(1));
      }
      Matcher h = HEIGHT.matcher(source);
      if (h.find()) {
        height = Double.parseDouble(h.group(1));
      }
      Matcher slice = NINE_SLICE.matcher(source);
      if (slice.find()) {
        nineSlice = new int[] {Integer.parseInt(slice.group(1)),
            Integer.parseInt(slice.group(2)), Integer.parseInt(slice.group(3)),
            Integer.parseInt(slice.group(4))};
      }
      Matcher center = ROTATION_CENTER.matcher(source);
      if (center.find()) {
        rotationCenter = new double[] {
            Double.parseDouble(center.group(1)), Double.parseDouble(center.group(2))};
      }
    } catch (IOException | RuntimeException ignored) {
      // unreadable class: placeholder look
    }
    ClassDefaults fresh = new ClassDefaults(modified, costume, size, direction,
        style, java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(costumes)),
        hitbox, rotationCenter, width, height, nineSlice);
    defaults.put(className, fresh);
    return fresh;
  }

  Image backdrop(StageModel model) {
    if (model.backdrops().isEmpty()) {
      return null;
    }
    StageModel.Backdrop first = model.backdrops().get(0);
    String ref = first.path() != null ? first.path() : first.name();
    return CostumeView.costume(ref, project.root());
  }

  /**
   * Draws the stage into {@code g} at {@code scale} (canvas pixels per stage
   * unit). Hidden sprites are drawn faintly so they stay selectable.
   */
  void draw(GraphicsContext g, StageModel model, double scale, boolean grid) {
    stageWidth = model.width();
    double w = model.width() * scale;
    double h = model.height() * scale;
    g.setFill(Color.WHITE);
    g.fillRect(0, 0, w, h);
    Image backdrop = backdrop(model);
    if (backdrop != null) {
      g.setImageSmoothing(true);
      g.drawImage(backdrop, 0, 0, w, h);
    }
    drawMap(g, w, h, scale);
    if (grid) {
      g.setLineWidth(1);
      g.setStroke(Color.rgb(0, 0, 0, backdrop == null ? 0.06 : 0.10));
      for (double x = 0; x <= model.width() / 2.0; x += 20) {
        line(g, w / 2 + x * scale, 0, w / 2 + x * scale, h);
        line(g, w / 2 - x * scale, 0, w / 2 - x * scale, h);
      }
      for (double y = 0; y <= model.height() / 2.0; y += 20) {
        line(g, 0, h / 2 + y * scale, w, h / 2 + y * scale);
        line(g, 0, h / 2 - y * scale, w, h / 2 - y * scale);
      }
      g.setStroke(Color.rgb(0, 0, 0, 0.22));
      line(g, w / 2, 0, w / 2, h);
      line(g, 0, h / 2, w, h / 2);
    }
    // sprites live in the world: the camera moves the view over them
    g.save();
    g.translate(-cameraX * scale, cameraY * scale);
    for (SpriteRef ref : model.sprites()) {
      if (!ref.hasPosition() && !ref.isAdded()) {
        continue;
      }
      // a UI sprite (setUI(true), UISprite) stays where it is on the screen
      g.save();
      g.translate((cameraX - cameraX(ref)) * scale, -(cameraY - cameraY(ref)) * scale);
      drawSprite(g, ref, look(ref), w, h, scale);
      g.restore();
    }
    g.restore();
    drawGhosts(g, w, h, scale);
    drawMapObjects(g, w, h, scale);
  }

  private List<org.openpatch.scratch4j.core.region.CodeSprites.Ghost> ghosts = List.of();
  private double spriteAlpha = 1;
  private int ghostLine = -1;

  /** Outlines the ghosts one line of code makes (-1: none); whether that changed. */
  boolean highlightGhostLine(int line) {
    boolean changed = ghostLine != line;
    ghostLine = line;
    return changed;
  }

  int highlightedGhostLine() {
    return ghostLine;
  }

  /** Sprites the stage's own code adds (worked out without running), drawn faded. */
  void setGhosts(List<org.openpatch.scratch4j.core.region.CodeSprites.Ghost> ghosts) {
    this.ghosts = List.copyOf(ghosts);
  }

  List<org.openpatch.scratch4j.core.region.CodeSprites.Ghost> ghosts() {
    return ghosts;
  }

  private void drawGhosts(GraphicsContext g, double w, double h, double scale) {
    if (ghosts.isEmpty()) return;
    g.save();
    g.translate(-cameraX * scale, cameraY * scale);
    spriteAlpha = 0.4;
    for (var ghost : ghosts) {
      SpriteRef ref = new SpriteRef("ghost", ghost.type());
      ref.setPosition(ghost.x(), ghost.y());
      spriteAlpha = ghost.line() == ghostLine ? 0.85 : 0.4;
      Look look = look(ref);
      drawSprite(g, ref, look, w, h, scale);
      if (ghost.line() == ghostLine) {
        // the caret's line made this one: outlined like a selection
        double cx = w / 2 + ghost.x() * scale;
        double cy = h / 2 - ghost.y() * scale;
        double hw = Math.max(8, look.width() * scale / 2);
        double hh = Math.max(8, look.height() * scale / 2);
        g.setStroke(Color.web("#4c97ff"));
        g.setLineWidth(1.5);
        g.setLineDashes(5, 4);
        g.strokeRect(cx - hw, cy - hh, hw * 2, hh * 2);
        g.setLineDashes((double[]) null);
      }
    }
    spriteAlpha = 1;
    g.restore();
  }

  /** The ghost under a canvas point, or null. */
  org.openpatch.scratch4j.core.region.CodeSprites.Ghost ghostAt(double canvasX,
      double canvasY, double w, double h, double scale) {
    for (int i = ghosts.size() - 1; i >= 0; i--) {
      var ghost = ghosts.get(i);
      SpriteRef ref = new SpriteRef("ghost", ghost.type());
      ref.setPosition(ghost.x(), ghost.y());
      Look look = look(ref);
      double cx = w / 2 + (ghost.x() - cameraX) * scale;
      double cy = h / 2 - (ghost.y() - cameraY) * scale;
      double hw = Math.max(8, look.width() * scale / 2);
      double hh = Math.max(8, look.height() * scale / 2);
      if (Math.abs(canvasX - cx) <= hw && Math.abs(canvasY - cy) <= hh) return ghost;
    }
    return null;
  }

  private static void line(GraphicsContext g, double x1, double y1, double x2, double y2) {
    // half-pixel offset: crisp 1px lines
    g.strokeLine(Math.round(x1) + 0.5, Math.round(y1) + 0.5,
        Math.round(x2) + 0.5, Math.round(y2) + 0.5);
  }

  private void drawSprite(GraphicsContext g, SpriteRef ref, Look look,
      double w, double h, double scale) {
    double x = ref.hasPosition() ? ref.x() : 0;
    double y = ref.hasPosition() ? ref.y() : 0;
    double dw = look.width() * scale;
    double dh = look.height() * scale;
    g.save();
    g.translate(w / 2 + (x + look.offsetX()) * scale, h / 2 + (-y + look.offsetY()) * scale);
    g.rotate(look.rotationAngle());
    if (look.mirrored()) {
      g.scale(-1, 1);
    }
    // hidden sprites faintly; a ghost or live overlay keeps its own fading on top
    g.setGlobalAlpha(spriteAlpha * (ref.isVisible() ? 1 : 0.3));
    if (look.isText()) {
      drawText(g, look, dw, dh, scale);
    } else if (look.image() == null) {
      g.setFill(Color.web("#855cd6", 0.25));
      g.setStroke(Color.web("#855cd6"));
      g.fillRoundRect(-dw / 2, -dh / 2, dw, dh, 8, 8);
      g.strokeRoundRect(-dw / 2, -dh / 2, dw, dh, 8, 8);
      g.setFill(Color.web("#3d2a6b"));
      g.fillText(ref.name(), -dw / 2 + 4, 4);
    } else if (look.slice() != null) {
      g.setImageSmoothing(scale < 1.5);
      drawNineSlice(g, look, -dw / 2, -dh / 2, scale);
    } else {
      g.setImageSmoothing(scale < 1.5);
      g.drawImage(look.image(), -dw / 2, -dh / 2, dw, dh);
    }
    g.restore();
  }

  /** Corners keep their (size-scaled) pixel size, edges and centre stretch — as the library draws. */
  private static void drawNineSlice(GraphicsContext g, Look look, double left, double top,
      double scale) {
    Image image = look.image();
    double iw = image.getWidth();
    double ih = image.getHeight();
    int[] o = look.originalSlice();
    int[] d = look.slice();
    // source columns/rows (top, right, bottom, left order like setNineSlice)
    double[] sx = {0, o[3], iw - o[1], iw};
    double[] sy = {0, o[0], ih - o[2], ih};
    double[] dx = {0, d[3], look.width() - d[1], look.width()};
    double[] dy = {0, d[0], look.height() - d[2], look.height()};
    for (int row = 0; row < 3; row++) {
      for (int col = 0; col < 3; col++) {
        double sw = sx[col + 1] - sx[col];
        double sh = sy[row + 1] - sy[row];
        double tw = dx[col + 1] - dx[col];
        double th = dy[row + 1] - dy[row];
        if (sw <= 0 || sh <= 0 || tw <= 0 || th <= 0) {
          continue;
        }
        g.drawImage(image, sx[col], sy[row], sw, sh,
            left + dx[col] * scale, top + dy[row] * scale, tw * scale, th * scale);
      }
    }
  }

  private static void drawText(GraphicsContext g, Look look, double dw, double dh, double scale) {
    g.setFont(javafx.scene.text.Font.font(TEXT_FONT.getFamily(), TEXT_SIZE * scale));
    g.setTextBaseline(javafx.geometry.VPos.TOP);
    g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
    double textTop = -dh / 2;
    double textLeft = -dw / 2;
    if (!"PLAIN".equals(look.textStyle())) {
      g.setFill(Color.WHITE);
      g.setStroke(Color.rgb(218, 218, 218));
      g.setLineWidth(2 * scale);
      double radius = 32 * scale;
      g.fillRoundRect(-dw / 2, -dh / 2, dw, dh, radius, radius);
      g.strokeRoundRect(-dw / 2, -dh / 2, dw, dh, radius, radius);
      if ("SPEAK".equals(look.textStyle()) || "THINK".equals(look.textStyle())) {
        // the bubble's tail, pointing down-left like Scratch's
        g.setFill(Color.WHITE);
        g.fillPolygon(new double[] {-dw / 2 + 16 * scale, -dw / 2 + 32 * scale,
            -dw / 2 + 10 * scale}, new double[] {dh / 2 - 1, dh / 2 - 1, dh / 2 + 12 * scale}, 3);
        g.strokePolyline(new double[] {-dw / 2 + 16 * scale, -dw / 2 + 10 * scale,
            -dw / 2 + 32 * scale}, new double[] {dh / 2, dh / 2 + 12 * scale, dh / 2}, 3);
      }
      textTop += TEXT_PADDING * scale;
      textLeft += TEXT_PADDING * scale;
    }
    g.setFill(Color.rgb(120, 120, 120));
    for (int i = 0; i < look.lines().size(); i++) {
      g.fillText(look.lines().get(i), textLeft, textTop + i * TEXT_LEADING * scale);
    }
  }

  /** Designer preview of an explicit polygon hitbox, or costume bounds otherwise. */
  void drawHitboxes(GraphicsContext g, StageModel model, double scale) {
    g.save();
    g.translate(-cameraX * scale, cameraY * scale);
    drawHitboxesInWorld(g, model, scale);
    g.restore();
  }

  private void drawHitboxesInWorld(GraphicsContext g, StageModel model, double scale) {
    g.setStroke(Color.web("#e8a317"));
    g.setLineWidth(2);
    g.setLineDashes(5, 3);
    for (SpriteRef ref : model.sprites()) {
      if (ref.isText()) {
        continue;
      }
      g.save();
      g.translate((cameraX - cameraX(ref)) * scale, -(cameraY - cameraY(ref)) * scale);
      Look look = look(ref);
      ClassDefaults defaults = defaultsOf(ref.type());
      double x = ref.hasPosition() ? ref.x() : 0;
      double y = ref.hasPosition() ? ref.y() : 0;
      g.save();
      g.translate(model.width() * scale / 2 + x * scale,
          model.height() * scale / 2 - y * scale);
      g.rotate(look.rotationAngle());
      if (look.mirrored()) {
        g.scale(-1, 1);
      }
      if (defaults.hitbox() == null) {
        g.strokeRect(-look.width() * scale / 2, -look.height() * scale / 2,
            look.width() * scale, look.height() * scale);
      } else {
        double[] points = defaults.hitbox();
        double cx = defaults.rotationCenter() == null
            ? (look.image() == null ? 48 : look.image().getWidth()) / 2
            : defaults.rotationCenter()[0];
        double cy = defaults.rotationCenter() == null
            ? (look.image() == null ? 48 : look.image().getHeight()) / 2
            : defaults.rotationCenter()[1];
        double factor = look.size() / 100.0 * scale;
        double[] xs = new double[points.length / 2];
        double[] ys = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
          xs[i] = (points[i * 2] - cx) * factor;
          ys[i] = (points[i * 2 + 1] - cy) * factor;
        }
        g.strokePolygon(xs, ys, xs.length);
      }
      g.restore();
      g.restore();
    }
    g.setLineDashes();
  }

  /** A thumbnail of a stage class (the stage selector's cards). */
  Image thumbnail(String stageClass, double width) {
    StageModel model;
    try {
      String source = Files.readString(project.root().resolve(stageClass + ".java"),
          StandardCharsets.UTF_8);
      // the stage's own map and camera, as the designer shows them
      scan(source);
      model = StageDocument.read(source).model();
      if (StageDocument.declaredSize(source).isEmpty()) {
        StageModel sized = model;
        windowSize().ifPresent(size -> sized.size(size.width(), size.height()));
      }
      defaultCamera(model.width(), model.height());
    } catch (IOException | RuntimeException e) {
      model = StageModel.create();
    }
    double scale = width / model.width();
    Canvas canvas = new Canvas(width, model.height() * scale);
    draw(canvas.getGraphicsContext2D(), model, scale, false);
    SnapshotParameters params = new SnapshotParameters();
    params.setFill(Color.TRANSPARENT);
    return canvas.snapshot(params, null);
  }
}
