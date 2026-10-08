package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where projects go. By default a "Scratch for Java" folder in the user's
 * documents (on Linux the XDG documents folder, so {@code ~/Dokumente} on a
 * German desktop; on Windows also a OneDrive documents folder), created when
 * the first project is saved there. School IT can point every machine at one
 * place with {@code SCRATCH4J_PROJECTS_DIR}. Once a project has been created
 * or opened, its parent folder is remembered and every project dialog starts
 * there.
 */
final class ProjectsFolder {

  static final String FOLDER_NAME = "Scratch for Java";

  private ProjectsFolder() {}

  /** The folder to offer now: the last one used if it still exists, else the default. */
  static Path current() {
    String last = Prefs.projectsFolder();
    if (!last.isBlank()) {
      Path dir = Path.of(last);
      if (Files.isDirectory(dir)) {
        return dir;
      }
    }
    return defaultFolder(System.getenv(), System.getProperty("os.name", ""),
        Path.of(System.getProperty("user.home")));
  }

  /** Remembers the folder a project was created in or opened from. */
  static void remember(Path folder) {
    if (folder != null) {
      Prefs.projectsFolder(folder.toAbsolutePath().normalize().toString());
    }
  }

  /** {@code dir}, or its nearest existing parent: what a folder chooser can start in. */
  static java.io.File existing(Path dir) {
    Path p = dir.toAbsolutePath();
    while (p != null && !Files.isDirectory(p)) {
      p = p.getParent();
    }
    return p == null ? null : p.toFile();
  }

  /** The default projects folder for this environment, operating system and home folder. */
  static Path defaultFolder(Map<String, String> env, String osName, Path home) {
    String override = env.get("SCRATCH4J_PROJECTS_DIR");
    if (override != null && !override.isBlank()) {
      return Path.of(override);
    }
    return documents(env, osName, home).resolve(FOLDER_NAME);
  }

  /** The user's documents folder, or the home folder when there is none. */
  static Path documents(Map<String, String> env, String osName, Path home) {
    String os = osName.toLowerCase(Locale.ROOT);
    if (os.contains("linux") || os.contains("bsd")) {
      String config = env.get("XDG_CONFIG_HOME");
      Path dirs = (config == null || config.isBlank() ? home.resolve(".config") : Path.of(config))
          .resolve("user-dirs.dirs");
      Path xdg = xdgDocuments(dirs, home);
      if (xdg != null && Files.isDirectory(xdg) && !xdg.equals(home)) {
        return xdg;
      }
    }
    if (os.contains("win")) {
      String oneDrive = env.get("OneDrive");
      if (oneDrive != null && !oneDrive.isBlank() && !Files.isDirectory(home.resolve("Documents"))) {
        for (String name : List.of("Documents", "Dokumente")) {
          Path dir = Path.of(oneDrive).resolve(name);
          if (Files.isDirectory(dir)) {
            return dir;
          }
        }
      }
    }
    Path documents = home.resolve("Documents");
    return Files.isDirectory(documents) ? documents : home;
  }

  private static final Pattern XDG_DOCUMENTS =
      Pattern.compile("^\\s*XDG_DOCUMENTS_DIR\\s*=\\s*\"?([^\"]*)\"?\\s*$", Pattern.MULTILINE);

  /** XDG_DOCUMENTS_DIR from {@code user-dirs.dirs} ("$HOME/Dokumente"), or null. */
  static Path xdgDocuments(Path userDirs, Path home) {
    try {
      if (!Files.isRegularFile(userDirs)) {
        return null;
      }
      Matcher m = XDG_DOCUMENTS.matcher(Files.readString(userDirs, StandardCharsets.UTF_8));
      if (!m.find()) {
        return null;
      }
      String value = m.group(1);
      if (value.startsWith("$HOME")) {
        String rest = value.substring("$HOME".length()).replaceFirst("^/", "");
        return rest.isEmpty() ? home : home.resolve(rest);
      }
      Path path = Path.of(value);
      return path.isAbsolute() ? path : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
