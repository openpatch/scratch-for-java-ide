package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a project's build output goes: compiled classes for the check, a
 * run, hot swap, the stage previews and exports. Nothing in it needs to last;
 * everything is compiled again when it is missing.
 *
 * <p>On Windows it is {@code %LOCALAPPDATA%\scratch4j\<project-hash>}, on the
 * computer's own disk. At schools the projects live on a network drive, where
 * every file is slow, and compiling there after each save kept the drive busy;
 * the local disk may be wiped at a reboot, which costs only one full compile.
 * Elsewhere, or when that folder cannot be made, it stays in the project:
 * {@code .scratch4j/build}. The settings can choose another folder for all
 * projects ({@link #setBase}); each project gets its own folder in it.
 */
public final class BuildFolder {

  private static final Map<Path, Path> FOLDERS = new ConcurrentHashMap<>();
  /** The folder chosen in the settings, or null for {@link #defaultBase()}. */
  private static volatile Path chosen;

  private BuildFolder() {}

  /**
   * The folder the projects' build folders go in, as chosen in the settings
   * (null or blank: the default). Applies to the next build of each project.
   */
  public static void setBase(Path base) {
    chosen = base;
    FOLDERS.clear();
  }

  /** {@code %LOCALAPPDATA%\scratch4j} on Windows; null elsewhere (in the project). */
  public static Path defaultBase() {
    return defaultBase(System.getenv("LOCALAPPDATA"));
  }

  static Path defaultBase(String localAppData) {
    return localAppData == null || localAppData.isBlank()
        ? null : Path.of(localAppData, "scratch4j");
  }

  /** The build folder of the project at {@code projectRoot}. */
  public static Path of(Path projectRoot) {
    Path root = projectRoot.toAbsolutePath().normalize();
    Path base = chosen;
    Path folders = base != null ? base : defaultBase();
    return FOLDERS.computeIfAbsent(root, r -> of(r, folders));
  }

  /** The build folder of a project in {@code base} (null: in the project). */
  static Path of(Path projectRoot, Path base) {
    Path root = projectRoot.toAbsolutePath().normalize();
    Path inProject = root.resolve(".scratch4j/build");
    if (base == null) return inProject;
    Path local = base.resolve(hash(root));
    try {
      Files.createDirectories(local);
      // which project the folder belongs to, for a person looking in there
      Path note = local.resolve("project.txt");
      if (!Files.isRegularFile(note)) {
        Files.writeString(note, root + System.lineSeparator(), StandardCharsets.UTF_8);
      }
      return local;
    } catch (IOException | RuntimeException e) {
      return inProject;
    }
  }

  /** 16 hex digits of the project folder's SHA-256 (Windows paths ignore case). */
  static String hash(Path root) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(root.toString().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
