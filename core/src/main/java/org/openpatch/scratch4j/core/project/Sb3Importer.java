package org.openpatch.scratch4j.core.project;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a Scratch 3 project ({@code .sb3}) as a classes-first Scratch for
 * Java project: one {@code Sprite} subclass per Scratch sprite with its
 * costumes, sounds and rotation centre, and a stage class whose designer
 * regions hold the backdrops, stage sounds and every sprite at its position,
 * direction, size, costume, visibility, rotation style and layer.
 *
 * <p>Scripts are not converted (they are rebuilt in Java with the block
 * palette); the result lists what needs attention. Bitmap costumes are copied
 * (Scratch stores them at {@code bitmapResolution} 2, so sizes are halved),
 * vector costumes are rendered to PNG at 2x with Batik (the SVG is kept),
 * and MP3 sounds are converted to WAV by the caller-supplied converter.
 */
public final class Sb3Importer {

  /** The imported project and what the user should know about it. */
  public record Result(Path root, String stageClass, List<String> spriteClasses,
      List<String> notes) {}

  private static final Pattern NOT_FILE_NAME = Pattern.compile("[^A-Za-z0-9_-]");

  /** Names a sprite class must not take: java.lang and the library's own types. */
  private static final Set<String> RESERVED = Set.of("Class", "Object", "String", "System",
      "Math", "Integer", "Double", "Boolean", "Character", "Long", "Float", "Thread", "Runtime",
      "Record", "Enum", "Override", "Exception", "Error", "Number", "Iterable", "Process",
      "Sprite", "AnimatedSprite", "UISprite", "Stage", "Window", "Text", "Pen", "Timer",
      "Color", "Random", "Operators", "KeyCode", "MouseCode", "Vector2", "Hitbox", "Shape",
      "Clock", "Layer", "RotationStyle", "TextStyle", "TextAlign", "MyStage");

  private Sb3Importer() {}

  /**
   * Creates {@code parentDir/name} from the {@code .sb3}. {@code mp3ToWav}
   * converts an MP3 file to a WAV file next to it (null: MP3s are kept and
   * flagged).
   */
  public static Result importProject(Path sb3, Path parentDir, String name, Path libraryJar,
      Function<Path, Path> mp3ToWav) throws IOException {
    Path root = parentDir.resolve(name);
    if (Files.exists(root)) {
      throw new IOException("Folder already exists: " + root);
    }
    try (ZipFile zip = new ZipFile(sb3.toFile())) {
      ZipEntry projectEntry = zip.getEntry("project.json");
      if (projectEntry == null) {
        throw new IOException(sb3.getFileName() + " is not a Scratch 3 project (no project.json)");
      }
      JsonNode project;
      try (InputStream in = zip.getInputStream(projectEntry)) {
        project = JsonMapper.builder().build().readTree(in);
      }
      Files.createDirectories(root.resolve("assets/images"));
      Files.createDirectories(root.resolve("assets/sounds"));
      Importer importer = new Importer(zip, root, mp3ToWav);
      Result result = importer.run(project);
      Path libs = root.resolve("+libs");
      Files.createDirectories(libs);
      if (libraryJar != null) {
        Files.copy(libraryJar, libs.resolve(libraryJar.getFileName()),
            StandardCopyOption.REPLACE_EXISTING);
      }
      ProjectSettings settings = new ProjectSettings();
      settings.startStage = result.stageClass();
      settings.save(root);
      return result;
    }
  }

  private static final class Importer {
    private final ZipFile zip;
    private final Path root;
    private final Function<Path, Path> mp3ToWav;
    private final List<String> notes = new ArrayList<>();
    private final Set<String> usedFiles = new HashSet<>();
    private final Set<String> classNames = new HashSet<>(Set.of("Stage", "Sprite", "Window",
        "Text", "MyStage", "ScratchValues"));
    private String globalFields = "";
    private boolean needsValues;

    Importer(ZipFile zip, Path root, Function<Path, Path> mp3ToWav) {
      this.zip = zip;
      this.root = root;
      this.mp3ToWav = mp3ToWav;
    }

    private record Costume(String name, String path, double centerX, double centerY,
        double resolution) {}

    private record SoundFile(String name, String path) {}

    Result run(JsonNode project) throws IOException {
      JsonNode stage = null;
      List<JsonNode> sprites = new ArrayList<>();
      for (JsonNode target : project.path("targets")) {
        if (target.path("isStage").asBoolean(false)) {
          stage = target;
        } else {
          sprites.add(target);
        }
      }
      sprites.sort((a, b) -> Integer.compare(a.path("layerOrder").asInt(0),
          b.path("layerOrder").asInt(0)));

      StringBuilder fields = new StringBuilder();
      StringBuilder setup = new StringBuilder();
      String indent = "    ";
      if (stage != null) {
        for (Costume backdrop : costumes(stage, "Stage")) {
          setup.append(indent).append("this.addBackdrop(").append(literal(backdrop.name()))
              .append(", ").append(literal(backdrop.path())).append(");\n");
        }
        int current = stage.path("currentCostume").asInt(0);
        if (current > 0) {
          notes.add("The stage started on backdrop " + (current + 1)
              + "; switch to it in code with this.switchBackdrop(...).");
        }
        for (SoundFile sound : sounds(stage, "Stage")) {
          setup.append(indent).append("this.addSound(").append(literal(sound.name()))
              .append(", ").append(literal(sound.path())).append(");\n");
        }
      }

      List<String> spriteClasses = new ArrayList<>();
      Set<String> fieldNames = new HashSet<>();
      String stageClassName = "MyStage";
      // class names first: scripts refer to other sprites ("touching Cat?")
      Map<String, String> classOf = new java.util.LinkedHashMap<>();
      for (JsonNode sprite : sprites) {
        String scratchName = sprite.path("name").asString("Sprite");
        classOf.put(scratchName, uniqueClass(scratchName));
      }
      // stage variables are shared by all sprites: statics on the stage class
      Map<String, Sb3Scripts.Variable> globals = new java.util.HashMap<>();
      Set<String> globalNames = new HashSet<>();
      globalFields = stage == null ? "" : declare(stage, true, stageClassName, globals,
          globalNames);
      for (JsonNode sprite : sprites) {
        String scratchName = sprite.path("name").asString("Sprite");
        String className = classOf.get(scratchName);
        spriteClasses.add(className);
        List<Costume> costumes = costumes(sprite, className);
        List<SoundFile> sounds = sounds(sprite, className);
        writeSpriteClass(className, scratchName, sprite, costumes, sounds, globals, classOf);

        String field = uniqueField(className, fieldNames);
        fields.append("  ").append(className).append(' ').append(field).append(";\n");
        int current = Math.max(0, Math.min(sprite.path("currentCostume").asInt(0),
            costumes.size() - 1));
        double resolution = costumes.isEmpty() ? 1 : costumes.get(current).resolution();
        setup.append(indent).append(field).append(" = new ").append(className).append("();\n");
        if (current > 0) {
          setup.append(indent).append(field).append(".switchCostume(")
              .append(literal(costumes.get(current).name())).append(");\n");
        }
        setup.append(indent).append(field).append(".setPosition(")
            .append(number(sprite.path("x").asDouble(0))).append(", ")
            .append(number(sprite.path("y").asDouble(0))).append(");\n");
        double direction = sprite.path("direction").asDouble(90);
        if (direction != 90) {
          setup.append(indent).append(field).append(".setDirection(").append(number(direction))
              .append(");\n");
        }
        String style = switch (sprite.path("rotationStyle").asString("all around")) {
          case "left-right" -> "LEFT_RIGHT";
          case "don't rotate" -> "DONT";
          default -> null;
        };
        if (style != null) {
          setup.append(indent).append(field)
              .append(".setRotationStyle(RotationStyle.").append(style)
              .append(");\n");
        }
        double size = sprite.path("size").asDouble(100) / resolution;
        if (Math.abs(size - 100) > 0.001) {
          setup.append(indent).append(field).append(".setSize(")
              .append(number(Math.round(size * 100) / 100.0)).append(");\n");
        }
        if (!sprite.path("visible").asBoolean(true)) {
          setup.append(indent).append(field).append(".hide();\n");
        }
        setup.append(indent).append("this.add(").append(field).append(");\n");
      }

      String stageClass = stageClassName;
      writeStageClass(stageClass, stage, fields.toString(), setup.toString(), globals, classOf);
      if (needsValues) {
        Files.writeString(root.resolve("ScratchValues.java"), Sb3Scripts.VALUES_CLASS,
            StandardCharsets.UTF_8);
      }
      Files.writeString(root.resolve("README.md"), "# " + root.getFileName()
          + "\n\nImported from a Scratch 3 project. Run " + stageClass + ".\n"
          + (notes.isEmpty() ? "" : "\n## Notes from the import\n\n- "
              + String.join("\n- ", notes) + "\n"), StandardCharsets.UTF_8);
      return new Result(root, stageClass, spriteClasses, notes);
    }

    private void writeSpriteClass(String className, String scratchName, JsonNode sprite,
        List<Costume> costumes, List<SoundFile> sounds, Map<String, Sb3Scripts.Variable> globals,
        Map<String, String> classOf) throws IOException {
      StringBuilder setup = new StringBuilder();
      String indent = "    ";
      for (Costume costume : costumes) {
        setup.append(indent).append("this.addCostume(").append(literal(costume.name()))
            .append(", ").append(literal(costume.path())).append(");\n");
      }
      if (!costumes.isEmpty()) {
        Costume first = costumes.get(0);
        setup.append(indent).append("this.setRotationCenter(")
            .append(number(Math.round(first.centerX() * first.resolution())))
            .append(", ").append(number(Math.round(first.centerY() * first.resolution())))
            .append(");\n");
      }
      for (SoundFile sound : sounds) {
        setup.append(indent).append("this.addSound(").append(literal(sound.name()))
            .append(", ").append(literal(sound.path())).append(");\n");
      }
      Map<String, Sb3Scripts.Variable> visible = new java.util.HashMap<>(globals);
      String fields = declare(sprite, false, className, visible, new HashSet<>());
      Sb3Scripts.Output scripts = new Sb3Scripts(sprite, false, "MyStage", visible, classOf)
          .convert(fields);
      Files.writeString(root.resolve(className + ".java"),
          classSource(className, "Sprite", "Imported from the Scratch sprite \""
              + scratchName.replace("*/", "") + "\".", scripts, setup.toString(), null),
          StandardCharsets.UTF_8);
      note(scratchName, className, scripts);
    }

    private void note(String owner, String className, Sb3Scripts.Output scripts) {
      needsValues |= scripts.needsValues();
      if (scripts.scripts() > 0) {
        notes.add(owner + ": " + scripts.scripts() + " script(s) converted into " + className
            + ".java" + (scripts.todos() == 0 ? "" : ", " + scripts.todos()
                + " block(s) left as // TODO Scratch: comments to finish by hand"));
      }
    }

    /**
     * One class: managed regions, the scripts' green-flag code after the
     * setup region, run() with the forever loops, then event methods.
     */
    private String classSource(String className, String base, String comment,
        Sb3Scripts.Output scripts, String setup, String stageFields) {
      StringBuilder sb = new StringBuilder();
      sb.append("import java.util.ArrayList;\nimport java.util.List;\nimport org.openpatch.scratch.*;\n\n");
      sb.append("// ").append(comment).append('\n');
      if (scripts.scripts() > 0) {
        sb.append("// Its scripts were converted").append(scripts.todos() == 0 ? "."
            : "; look for \"TODO Scratch\" for blocks to finish by hand.").append('\n');
      }
      sb.append("public class ").append(className).append(" extends ").append(base).append(" {\n");
      if (!scripts.fields().isEmpty()) {
        sb.append('\n').append(scripts.fields());
      }
      if (stageFields != null) {
        sb.append("\n  // scratch4j:begin fields").append(NewProject.MANAGED).append('\n')
            .append(stageFields).append("  // scratch4j:end fields\n");
      }
      sb.append("\n  public ").append(className).append("() {\n");
      if (stageFields != null) {
        sb.append("    super(480, 360);\n");
      }
      sb.append("    // scratch4j:begin setup").append(NewProject.MANAGED).append('\n')
          .append(setup).append("    // scratch4j:end setup\n");
      if (!scripts.constructor().isEmpty()) {
        sb.append("\n    // when green flag clicked\n").append(scripts.constructor());
      }
      sb.append("  }\n\n  public void run() {\n");
      if (!scripts.run().isEmpty()) {
        sb.append("    // forever (the library calls run() about 60 times per second)\n")
            .append(scripts.run());
      }
      sb.append("  }\n");
      sb.append(scripts.methods());
      if (stageFields != null) {
        sb.append("\n  public static void main(String[] args) {\n    new ").append(className)
            .append("();\n  }\n");
      }
      return sb.append("}\n").toString();
    }

    private void writeStageClass(String className, JsonNode stage, String fields, String setup,
        Map<String, Sb3Scripts.Variable> globals, Map<String, String> classOf)
        throws IOException {
      Map<String, Sb3Scripts.Variable> own = new java.util.HashMap<>();
      for (var entry : globals.entrySet()) {
        Sb3Scripts.Variable v = entry.getValue();
        own.put(entry.getKey(), new Sb3Scripts.Variable(
            v.java().substring(v.java().indexOf('.') + 1), true, v.number(), v.list()));
      }
      Sb3Scripts.Output scripts = stage == null
          ? new Sb3Scripts.Output(globalFields, "", "", "", 0, 0, false)
          : new Sb3Scripts(stage, true, className, own, classOf).convert(globalFields);
      Files.writeString(root.resolve(className + ".java"), classSource(className, "Stage",
          "Imported from Scratch: the stage with its backdrops and sprites.", scripts, setup,
          fields), StandardCharsets.UTF_8);
      note("Stage", className, scripts);
    }

    /**
     * Field declarations for a target's variables and lists (numbers as
     * double, text as String, lists as ArrayList), registering their Java
     * names. Globals are statics read as {@code MyStage.score}.
     */
    private String declare(JsonNode target, boolean global, String owner,
        Map<String, Sb3Scripts.Variable> map, Set<String> used) {
      StringBuilder sb = new StringBuilder();
      for (var entry : target.path("variables").properties()) {
        JsonNode value = entry.getValue();
        String name = field(value.path(0).asString("variable"), used);
        JsonNode initial = value.path(1);
        boolean number = initial.isNumber() || initial.asString("").matches("-?[0-9]+(\\.[0-9]+)?");
        sb.append("  // Scratch variable \"").append(value.path(0).asString().replace("*/", ""))
            .append("\"\n  ").append(global ? "static " : "")
            .append(number ? "double " : "String ").append(name).append(" = ")
            .append(number ? number(initial.asDouble(0)) : Sb3Scripts.literal(initial.asString("")))
            .append(";\n");
        map.put(entry.getKey(), new Sb3Scripts.Variable(global ? owner + "." + name : name,
            global, number, false));
      }
      for (var entry : target.path("lists").properties()) {
        JsonNode value = entry.getValue();
        String name = field(value.path(0).asString("list"), used);
        StringBuilder items = new StringBuilder();
        for (JsonNode item : value.path(1)) {
          items.append(items.length() == 0 ? "" : ", ")
              .append(item.isNumber() ? number(item.asDouble()) : Sb3Scripts.literal(item.asString("")));
        }
        sb.append("  // Scratch list \"").append(value.path(0).asString().replace("*/", ""))
            .append("\"\n  ").append(global ? "static " : "")
            .append("ArrayList<Object> ").append(name).append(" = new ArrayList<>(List.of(")
            .append(items).append("));\n");
        map.put(entry.getKey(), new Sb3Scripts.Variable(global ? owner + "." + name : name,
            global, false, true));
      }
      return sb.toString();
    }

    private static String field(String scratchName, Set<String> used) {
      String base = javaName(scratchName, false);
      base = Character.toLowerCase(base.charAt(0)) + base.substring(1);
      String candidate = base;
      for (int i = 2; !used.add(candidate); i++) {
        candidate = base + i;
      }
      return candidate;
    }

    private List<Costume> costumes(JsonNode target, String owner) throws IOException {
      List<Costume> costumes = new ArrayList<>();
      for (JsonNode costume : target.path("costumes")) {
        // quotes and backslashes would need escapes the designer's subset does not read
        String name = costume.path("name").asString("costume").replace('"', '\'')
            .replace('\\', '/');
        String asset = costume.path("md5ext").asString(costume.path("assetId").asString("")
            + "." + costume.path("dataFormat").asString("png"));
        String format = costume.path("dataFormat").asString(
            asset.substring(asset.lastIndexOf('.') + 1)).toLowerCase(Locale.ROOT);
        double resolution = costume.path("bitmapResolution").asDouble(1);
        String base = fileName(owner + "-" + name);
        String path;
        if (format.equals("svg")) {
          // keep the original; the costume is the drawing rendered at 2x, like
          // Scratch's own bitmaps (so sizes and centres scale the same way)
          Path svg = root.resolve("assets/images/svg/" + base + ".svg");
          Files.createDirectories(svg.getParent());
          path = "assets/images/" + uniqueFile(base, "png");
          if (!copyEntry(asset, svg)) {
            notes.add(owner + " costume \"" + name + "\" is missing in the .sb3.");
            continue;
          }
          try {
            org.openpatch.scratch4j.core.assets.SvgRasterizer.toPng(Files.readAllBytes(svg), 2,
                root.resolve(path));
            resolution = 2;
          } catch (IOException drawing) {
            int[] size = svgSize(svg);
            placeholder(root.resolve(path), size[0], size[1], name);
            resolution = 1;
            notes.add(owner + " costume \"" + name + "\" could not be drawn ("
                + drawing.getMessage() + "): replaced by a placeholder (" + path + ").");
          }
        } else {
          path = "assets/images/" + uniqueFile(base, format);
          if (!copyEntry(asset, root.resolve(path))) {
            notes.add(owner + " costume \"" + name + "\" is missing in the .sb3.");
            continue;
          }
        }
        costumes.add(new Costume(name, path, costume.path("rotationCenterX").asDouble(0),
            costume.path("rotationCenterY").asDouble(0), resolution));
      }
      return costumes;
    }

    private List<SoundFile> sounds(JsonNode target, String owner) throws IOException {
      List<SoundFile> sounds = new ArrayList<>();
      for (JsonNode sound : target.path("sounds")) {
        String name = sound.path("name").asString("sound").replace('"', '\'')
            .replace('\\', '/');
        String asset = sound.path("md5ext").asString(sound.path("assetId").asString("")
            + "." + sound.path("dataFormat").asString("wav"));
        String format = sound.path("dataFormat").asString(
            asset.substring(asset.lastIndexOf('.') + 1)).toLowerCase(Locale.ROOT);
        String base = fileName(owner + "-" + name);
        Path file = root.resolve("assets/sounds/" + uniqueFile(base, format));
        if (!copyEntry(asset, file)) {
          notes.add(owner + " sound \"" + name + "\" is missing in the .sb3.");
          continue;
        }
        if (format.equals("mp3")) {
          Path wav = mp3ToWav == null ? null : mp3ToWav.apply(file);
          if (wav != null && Files.isRegularFile(wav)) {
            Files.deleteIfExists(file);
            file = wav;
          } else {
            notes.add(owner + " sound \"" + name + "\" is an MP3, which Scratch for Java "
                + "cannot play: open it in the sound editor and save it as WAV.");
          }
        }
        sounds.add(new SoundFile(name, root.relativize(file).toString().replace('\\', '/')));
      }
      return sounds;
    }

    private boolean copyEntry(String asset, Path target) throws IOException {
      ZipEntry entry = zip.getEntry(asset);
      if (entry == null) {
        return false;
      }
      Files.createDirectories(target.getParent());
      try (InputStream in = zip.getInputStream(entry)) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return true;
    }

    private String uniqueFile(String base, String extension) {
      String candidate = base + "." + extension;
      for (int i = 2; !usedFiles.add(candidate.toLowerCase(Locale.ROOT)); i++) {
        candidate = base + i + "." + extension;
      }
      return candidate;
    }

    private String uniqueClass(String scratchName) {
      String base = javaName(scratchName, true);
      String candidate = base;
      for (int i = 2; !classNames.add(candidate); i++) {
        candidate = base + i;
      }
      return candidate;
    }

    private static String uniqueField(String className, Set<String> used) {
      String base = Character.toLowerCase(className.charAt(0)) + className.substring(1);
      String candidate = base;
      for (int i = 2; !used.add(candidate); i++) {
        candidate = base + i;
      }
      return candidate;
    }
  }

  /** A Java identifier from a Scratch name: "Sprite 1" -> Sprite1, "3D Ball" -> Sprite3DBall. */
  static String javaName(String scratchName, boolean upper) {
    StringBuilder sb = new StringBuilder();
    boolean capitalize = upper;
    for (char c : scratchName.toCharArray()) {
      if (Character.isLetterOrDigit(c) && c < 128) {
        sb.append(capitalize ? Character.toUpperCase(c) : c);
        capitalize = false;
      } else {
        capitalize = true;
      }
    }
    String name = sb.toString();
    if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
      name = "Sprite" + name;
    }
    return javax.lang.model.SourceVersion.isKeyword(name) || RESERVED.contains(name)
        ? name + "Sprite" : name;
  }

  private static String fileName(String name) {
    String cleaned = NOT_FILE_NAME.matcher(name.replace(' ', '_')).replaceAll("");
    return cleaned.isEmpty() ? "asset" : cleaned;
  }

  private static String literal(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String number(double value) {
    return value == Math.floor(value) && !Double.isInfinite(value)
        ? Long.toString((long) value) : Double.toString(value);
  }

  /** width/height of an SVG (attributes or viewBox), 100x100 when unknown. */
  static int[] svgSize(Path svg) throws IOException {
    String text = Files.readString(svg, StandardCharsets.UTF_8);
    Matcher root = Pattern.compile("<svg[^>]*>", Pattern.DOTALL).matcher(text);
    if (!root.find()) {
      return new int[] {100, 100};
    }
    String tag = root.group();
    Map<String, Double> values = new HashMap<>();
    for (String attribute : List.of("width", "height")) {
      Matcher m = Pattern.compile("\\b" + attribute + "=\"([0-9.]+)").matcher(tag);
      if (m.find()) {
        values.put(attribute, Double.parseDouble(m.group(1)));
      }
    }
    Matcher viewBox = Pattern.compile("viewBox=\"[-0-9.]+[ ,]+[-0-9.]+[ ,]+([0-9.]+)[ ,]+([0-9.]+)")
        .matcher(tag);
    if (viewBox.find()) {
      values.putIfAbsent("width", Double.parseDouble(viewBox.group(1)));
      values.putIfAbsent("height", Double.parseDouble(viewBox.group(2)));
    }
    return new int[] {(int) Math.max(1, Math.round(values.getOrDefault("width", 100.0))),
        (int) Math.max(1, Math.round(values.getOrDefault("height", 100.0)))};
  }

  /** A dashed box with the costume's name: visible, the right size, clearly "draw me". */
  private static void placeholder(Path file, int width, int height, String label)
      throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(133, 92, 214, 60));
    g.fillRoundRect(0, 0, width - 1, height - 1, 12, 12);
    g.setColor(new Color(133, 92, 214));
    g.setStroke(new java.awt.BasicStroke(2, java.awt.BasicStroke.CAP_ROUND,
        java.awt.BasicStroke.JOIN_ROUND, 1, new float[] {6, 4}, 0));
    g.drawRoundRect(1, 1, width - 3, height - 3, 12, 12);
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(9, Math.min(14, height / 4))));
    g.drawString(label, 6, Math.min(height - 4, 18));
    g.dispose();
    Files.createDirectories(file.getParent());
    javax.imageio.ImageIO.write(image, "png", file.toFile());
  }
}
