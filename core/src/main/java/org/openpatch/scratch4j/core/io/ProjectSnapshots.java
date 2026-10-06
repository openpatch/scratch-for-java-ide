package org.openpatch.scratch4j.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Versions of a project's code to go back to: after every run that worked
 * (the window drew for a few seconds without a crash) the IDE keeps a copy of
 * the code files. Restoring keeps the current code as a version first, so a
 * restore can itself be undone.
 *
 * <p>Stored in {@code .scratch4j/versions/<millis>-<kind>/} with the files at
 * their project paths. Only code and maps are kept (images and sounds have
 * their own per-file history in {@link LocalHistory}).
 */
public final class ProjectSnapshots {

  /** Why a version was kept. */
  public enum Kind { RAN, BEFORE_RESTORE, MANUAL }

  /** One version: when, why, and its files (project path -> text). */
  public record Version(Path dir, Instant time, Kind kind) {

    public Map<String, String> files() throws IOException {
      Map<String, String> out = new TreeMap<>();
      try (Stream<Path> walk = Files.walk(dir)) {
        for (Path file : walk.filter(Files::isRegularFile).toList()) {
          out.put(dir.relativize(file).toString().replace('\\', '/'),
              Files.readString(file, StandardCharsets.UTF_8));
        }
      }
      return out;
    }
  }

  private static final Set<String> KEPT = Set.of(".java", ".tmx", ".tsx", ".frag", ".vert",
      ".glsl");
  private static final int MAX_VERSIONS = 40;

  private ProjectSnapshots() {}

  /** The project's code files now (project path -> text). */
  public static Map<String, String> current(Path root) throws IOException {
    Map<String, String> out = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        String path = root.relativize(file).toString().replace('\\', '/');
        if (path.startsWith(".scratch4j/") || path.startsWith("+libs/")
            || path.startsWith(".git/")) {
          continue;
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && KEPT.contains(name.substring(dot).toLowerCase())) {
          out.put(path, Files.readString(file, StandardCharsets.UTF_8));
        }
      }
    }
    return out;
  }

  /**
   * Keeps the current code as a version, unless it equals the newest one.
   * Returns the new version, or null when nothing changed.
   */
  public static Version keep(Path root, Kind kind) throws IOException {
    Map<String, String> files = current(root);
    List<Version> versions = list(root);
    if (!versions.isEmpty() && versions.get(0).files().equals(files)) {
      return null;
    }
    Path dir = versionsDir(root).resolve(System.currentTimeMillis() + "-"
        + kind.name().toLowerCase());
    while (Files.exists(dir)) {
      dir = dir.resolveSibling(dir.getFileName() + "x");
    }
    for (var entry : files.entrySet()) {
      Path target = dir.resolve(entry.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
    }
    Files.createDirectories(dir);
    prune(root);
    return new Version(dir, Instant.ofEpochMilli(millis(dir)), kind);
  }

  /** Newest first. */
  public static List<Version> list(Path root) throws IOException {
    Path dir = versionsDir(root);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<Version> out = new ArrayList<>();
    try (Stream<Path> children = Files.list(dir)) {
      for (Path child : children.filter(Files::isDirectory).toList()) {
        String name = child.getFileName().toString();
        int dash = name.indexOf('-');
        if (dash < 0) continue;
        try {
          Kind kind = Kind.valueOf(name.substring(dash + 1).replaceAll("x+$", "")
              .toUpperCase());
          out.add(new Version(child, Instant.ofEpochMilli(millis(child)), kind));
        } catch (IllegalArgumentException ignored) {
          // not a version folder
        }
      }
    }
    out.sort(Comparator.comparing(Version::time).thenComparing(v -> v.dir().toString())
        .reversed());
    return out;
  }

  /** The newest version kept after a run that worked, if any. */
  public static Version lastWorking(Path root) throws IOException {
    return list(root).stream().filter(v -> v.kind() == Kind.RAN).findFirst().orElse(null);
  }

  /**
   * Makes the code exactly the version's: its files written, code files that
   * did not exist then removed. The current code is kept as a version first.
   */
  public static void restore(Path root, Version version) throws IOException {
    keep(root, Kind.BEFORE_RESTORE);
    Map<String, String> wanted = version.files();
    for (String path : current(root).keySet()) {
      if (!wanted.containsKey(path)) {
        Files.deleteIfExists(root.resolve(path));
      }
    }
    for (var entry : wanted.entrySet()) {
      restoreFile(root, entry.getKey(), entry.getValue());
    }
  }

  /** Puts one file back as it was in a version (kept in the per-file history). */
  public static void restoreFile(Path root, String path, String text) throws IOException {
    Path target = root.resolve(path).normalize();
    if (!target.startsWith(root.normalize())) {
      throw new IOException("Outside the project: " + path);
    }
    Files.createDirectories(target.getParent());
    LocalHistory.writeString(root, target, text);
  }

  /** What changed from a version to now: path -> {then, now} (null where absent). */
  public static Map<String, String[]> changes(Path root, Version version) throws IOException {
    Map<String, String> then = version.files();
    Map<String, String> now = current(root);
    Map<String, String[]> out = new LinkedHashMap<>();
    Set<String> paths = new java.util.TreeSet<>(then.keySet());
    paths.addAll(now.keySet());
    for (String path : paths) {
      String a = then.get(path);
      String b = now.get(path);
      if (a == null || !a.equals(b)) {
        out.put(path, new String[] {a, b});
      }
    }
    return out;
  }

  private static void prune(Path root) throws IOException {
    List<Version> versions = list(root);
    for (int i = MAX_VERSIONS; i < versions.size(); i++) {
      try (Stream<Path> walk = Files.walk(versions.get(i).dir())) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  private static Path versionsDir(Path root) {
    return root.resolve(".scratch4j/versions");
  }

  private static long millis(Path dir) {
    String name = dir.getFileName().toString();
    try {
      return Long.parseLong(name.substring(0, name.indexOf('-')));
    } catch (RuntimeException e) {
      return 0;
    }
  }
}
