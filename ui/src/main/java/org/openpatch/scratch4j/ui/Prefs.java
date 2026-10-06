package org.openpatch.scratch4j.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Per-user IDE preferences (theme, language, editor font size, recent
 * projects). Backed by {@link Preferences}; every read falls back to a
 * default so a locked-down school PC without a writable store still works.
 *
 * <p>Portable mode (USB sticks, locked-down PCs): when a file named
 * {@code portable} sits in the app folder (the installed app's launcher passes
 * {@code scratch4j.appDir}), or {@code scratch4j.settingsDir} is set, the
 * settings live in {@code settings.properties} there instead.
 */
final class Prefs {

  private static final int MAX_RECENT = 8;
  private static final Store NODE = Store.open();

  /** The two homes for settings: the OS preference store or a portable file. */
  private interface Store {
    String get(String key, String fallback);

    void put(String key, String value);

    default boolean getBoolean(String key, boolean fallback) {
      return Boolean.parseBoolean(get(key, String.valueOf(fallback)));
    }

    default void putBoolean(String key, boolean value) {
      put(key, String.valueOf(value));
    }

    default int getInt(String key, int fallback) {
      try {
        return Integer.parseInt(get(key, String.valueOf(fallback)));
      } catch (NumberFormatException e) {
        return fallback;
      }
    }

    default void putInt(String key, int value) {
      put(key, String.valueOf(value));
    }

    static Store open() {
      Path dir = portableDir();
      if (dir != null) {
        return new FileStore(dir.resolve("settings.properties"));
      }
      Preferences node = Preferences.userRoot().node("org/openpatch/scratch4j/ide");
      return new Store() {
        @Override public String get(String key, String fallback) {
          return node.get(key, fallback);
        }

        @Override public void put(String key, String value) {
          node.put(key, value);
        }
      };
    }
  }

  /** The portable settings folder, or null for the OS store. */
  static Path portableDir() {
    String explicit = System.getProperty("scratch4j.settingsDir");
    if (explicit != null && !explicit.isBlank()) {
      return Path.of(explicit);
    }
    String appDir = System.getProperty("scratch4j.appDir");
    if (appDir != null && !appDir.isBlank()) {
      // <install>/lib/app is $APPDIR; the marker may sit there or in the install root
      for (Path candidate : List.of(Path.of(appDir), Path.of(appDir).resolve("../..").normalize())) {
        if (Files.isRegularFile(candidate.resolve("portable"))) {
          return candidate;
        }
      }
    }
    return null;
  }

  /** Settings in a properties file next to the app (portable mode). */
  private static final class FileStore implements Store {
    private final Path file;
    private final java.util.Properties values = new java.util.Properties();

    FileStore(Path file) {
      this.file = file;
      try (var in = Files.newInputStream(file)) {
        values.load(in);
      } catch (java.io.IOException ignored) {
        // first start: no settings yet
      }
    }

    @Override public synchronized String get(String key, String fallback) {
      return values.getProperty(key, fallback);
    }

    @Override public synchronized void put(String key, String value) {
      values.setProperty(key, value);
      try (var out = Files.newOutputStream(file)) {
        values.store(out, "Scratch for Java Studio (portable mode)");
      } catch (java.io.IOException ignored) {
        // read-only stick: settings last for this session only
      }
    }
  }

  private Prefs() {}

  static boolean darkTheme() {
    return NODE.getBoolean("theme.dark", false);
  }

  static void darkTheme(boolean dark) {
    NODE.putBoolean("theme.dark", dark);
  }

  static I18n.Language language() {
    try {
      return I18n.Language.valueOf(NODE.get("language", I18n.defaultLanguage().name()));
    } catch (IllegalArgumentException e) {
      return I18n.defaultLanguage();
    }
  }

  static void language(I18n.Language language) {
    NODE.put("language", language.name());
  }

  /** Text size of the whole UI (1, 1.25, 1.5). */
  static double uiScale() {
    try {
      double value = Double.parseDouble(NODE.get("ui.scale", "1"));
      return value >= 0.75 && value <= 2 ? value : 1;
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  static void uiScale(double scale) {
    NODE.put("ui.scale", String.valueOf(scale));
  }

  static boolean highContrast() {
    return NODE.getBoolean("ui.highContrast", false);
  }

  static void highContrast(boolean on) {
    NODE.putBoolean("ui.highContrast", on);
  }

  static int editorFontSize() {
    return NODE.getInt("editor.fontSize", 14);
  }

  static void editorFontSize(int size) {
    NODE.putInt("editor.fontSize", size);
  }

  /** Recently opened project folders that still exist, most recent first. */
  static List<Path> recentProjects() {
    List<Path> recent = new ArrayList<>();
    for (String entry : NODE.get("recent", "").split("\n")) {
      if (!entry.isBlank() && Files.isDirectory(Path.of(entry))) {
        recent.add(Path.of(entry));
      }
    }
    return recent;
  }

  static void addRecentProject(Path root) {
    List<Path> recent = new ArrayList<>(recentProjects());
    Path normalized = root.toAbsolutePath().normalize();
    recent.remove(normalized);
    recent.add(0, normalized);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < Math.min(MAX_RECENT, recent.size()); i++) {
      sb.append(recent.get(i)).append('\n');
    }
    NODE.put("recent", sb.toString());
  }
}
