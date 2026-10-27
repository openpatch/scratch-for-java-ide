package org.openpatch.scratch4j.ui;

import javafx.scene.paint.Color;
import org.openpatch.scratch4j.core.assets.GimpPalette;
import org.openpatch.scratch4j.core.project.ProjectSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The colour palettes the pickers offer: built-in {@code .gpl} resources and
 * the project's {@code assets/palettes/*.gpl}. A palette is named by an id,
 * {@code builtin:<name>} or the project-relative file. The project default
 * lives in {@code project.json}; the palette last chosen and the recently
 * picked custom colours are kept per project for this session.
 */
final class Palettes {

  static final String BUILTIN = "builtin:";
  static final String SCRATCH = BUILTIN + "scratch";
  static final String FOLDER = "assets/palettes";
  private static final List<String> BUILTINS = List.of("scratch", "pico-8", "greyscale");
  private static final int RECENT = 8;

  // keyed by project root; null = a picker outside a project
  private static final Map<Path, String> chosen = new HashMap<>();
  private static final Map<Path, Deque<Color>> recent = new HashMap<>();

  /** A palette in the picker's list. */
  record Ref(String id, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  private Palettes() {}

  /** The built-in palettes, then the project's (by file name). */
  static List<Ref> available(Path root) {
    List<Ref> refs = new ArrayList<>();
    for (String name : BUILTINS) {
      refs.add(new Ref(BUILTIN + name, I18n.t("colorpalette.builtin." + name)));
    }
    Path folder = root == null ? null : root.resolve(FOLDER);
    if (folder != null && Files.isDirectory(folder)) {
      try (Stream<Path> files = Files.list(folder)) {
        files.filter(Palettes::isPaletteFile).sorted().forEach(file -> {
          String fileName = file.getFileName().toString();
          refs.add(new Ref(FOLDER + "/" + fileName,
              fileName.substring(0, fileName.length() - GimpPalette.EXTENSION.length())));
        });
      } catch (IOException e) {
        // an unreadable folder only hides the project's palettes
      }
    }
    return refs;
  }

  static boolean isPaletteFile(Path file) {
    return Files.isRegularFile(file) && file.getFileName().toString()
        .toLowerCase(Locale.ROOT).endsWith(GimpPalette.EXTENSION);
  }

  static boolean isBuiltin(String id) {
    return id.startsWith(BUILTIN);
  }

  /** The palette {@code id}; the Scratch colours when it is missing or unreadable. */
  static GimpPalette load(Path root, String id) {
    try {
      if (isBuiltin(id)) {
        String name = id.substring(BUILTIN.length());
        if (BUILTINS.contains(name)) {
          try (InputStream in = Palettes.class.getResourceAsStream("palettes/" + name + ".gpl")) {
            if (in != null) {
              return GimpPalette.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
          }
        }
      } else if (root != null && isPaletteFile(root.resolve(id))) {
        return GimpPalette.read(root.resolve(id));
      }
    } catch (IOException | IllegalArgumentException e) {
      // fall through to the Scratch colours: a broken palette never blocks picking
    }
    return id.equals(SCRATCH) ? new GimpPalette("Scratch", 0, List.of()) : load(root, SCRATCH);
  }

  /** The project's default palette, if it still exists; else the Scratch colours. */
  static String projectDefault(Path root) {
    if (root == null) {
      return SCRATCH;
    }
    String id = ProjectSettings.load(root).palette;
    return exists(root, id) ? id : SCRATCH;
  }

  static void setProjectDefault(Path root, String id) throws IOException {
    ProjectSettings settings = ProjectSettings.load(root);
    settings.palette = id.equals(SCRATCH) ? "" : id;
    settings.save(root);
  }

  /** The palette pickers open on: the one last chosen this session, else the default. */
  static String current(Path root) {
    String id = chosen.get(root);
    return id != null && exists(root, id) ? id : projectDefault(root);
  }

  static void choose(Path root, String id) {
    chosen.put(root, id);
  }

  /** Custom colours picked lately, newest first. */
  static List<Color> recent(Path root) {
    return List.copyOf(recent.getOrDefault(root, new ArrayDeque<>()));
  }

  static void remember(Path root, Color color) {
    Deque<Color> colors = recent.computeIfAbsent(root, r -> new ArrayDeque<>());
    colors.remove(color);
    colors.addFirst(color);
    while (colors.size() > RECENT) {
      colors.removeLast();
    }
  }

  /**
   * Appends {@code color} (opaque: .gpl has no alpha) to palette {@code id}
   * and returns the id written to: a built-in palette is first copied into
   * the project's palette folder.
   */
  static String add(Path root, String id, Color color) throws IOException {
    GimpPalette palette = load(root, id);
    Path file;
    if (isBuiltin(id)) {
      String base = id.substring(BUILTIN.length());
      file = root.resolve(FOLDER).resolve(base + GimpPalette.EXTENSION);
      for (int i = 2; Files.exists(file); i++) {
        file = root.resolve(FOLDER).resolve(base + "-" + i + GimpPalette.EXTENSION);
      }
    } else {
      file = root.resolve(id);
    }
    List<GimpPalette.Entry> entries = new ArrayList<>(palette.entries());
    entries.add(entry(color));
    new GimpPalette(palette.name(), palette.columns(), entries).write(file);
    return FOLDER + "/" + file.getFileName();
  }

  static Color color(GimpPalette.Entry entry) {
    return Color.rgb(entry.red(), entry.green(), entry.blue());
  }

  static GimpPalette.Entry entry(Color color) {
    return new GimpPalette.Entry((int) Math.round(color.getRed() * 255),
        (int) Math.round(color.getGreen() * 255), (int) Math.round(color.getBlue() * 255), "");
  }

  private static boolean exists(Path root, String id) {
    if (id == null || id.isBlank()) {
      return false;
    }
    if (isBuiltin(id)) {
      return BUILTINS.contains(id.substring(BUILTIN.length()));
    }
    return root != null && isPaletteFile(root.resolve(id));
  }
}
