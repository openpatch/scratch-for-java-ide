package org.openpatch.scratch4j.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Zip helpers (java.util.zip — no external dependencies). */
public final class Zip {

  private Zip() {}

  /** Zips a whole directory (recursively) into {@code zipFile}; returns it. */
  public static Path zipDirectory(Path dir, Path zipFile) throws IOException {
    return zipDirectoryAs(dir, "", zipFile);
  }

  /**
   * Zips a whole directory with a prefix for every entry, so a project zip
   * unpacks into its own folder ({@code game/MyStage.java}, ...).
   */
  public static Path zipDirectoryAs(Path dir, String prefix, Path zipFile)
      throws IOException {
    return zipDirectoryAs(dir, prefix, zipFile, path -> false);
  }

  /**
   * Like {@link #zipDirectoryAs(Path, String, Path)}, leaving out every path
   * {@code skip} accepts (relative to {@code dir}) and the zip file itself.
   */
  public static Path zipDirectoryAs(Path dir, String prefix, Path zipFile,
      java.util.function.Predicate<Path> skip) throws IOException {
    Files.createDirectories(zipFile.getParent() == null ? dir : zipFile.getParent());
    Path target = zipFile.toAbsolutePath().normalize();
    Path partial = target.resolveSibling(target.getFileName() + ".part");
    try (ZipOutputStream zip = new ZipOutputStream(
        Files.newOutputStream(partial))) {
      zipDirectory(dir, dir, prefix, zip, path -> skip.test(dir.relativize(path))
          || path.toAbsolutePath().normalize().equals(target)
          || path.toAbsolutePath().normalize().equals(partial));
    }
    Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return zipFile;
  }

  private static void zipDirectory(Path root, Path current, String prefix,
      ZipOutputStream zip, java.util.function.Predicate<Path> skip) throws IOException {
    try (var files = Files.list(current)) {
      for (Path file : files.sorted().toList()) {
        if (skip.test(file)) {
          continue;
        }
        if (Files.isDirectory(file)) {
          zipDirectory(root, file, prefix, zip, skip);
        } else {
          zip.putNextEntry(new ZipEntry(prefix + root.relativize(file)
              .toString().replace('\\', '/')));
          try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(zip);
          }
          zip.closeEntry();
        }
      }
    }
  }

  /** Unzips an archive into {@code targetDir}. */
  public static void unzip(Path zipFile, Path targetDir) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        Path file = targetDir.resolve(entry.getName()).normalize();
        if (!file.startsWith(targetDir)) {
          continue; // zip-slip protection
        }
        if (entry.isDirectory()) {
          Files.createDirectories(file);
        } else {
          Files.createDirectories(file.getParent());
          try (OutputStream out = Files.newOutputStream(file)) {
            zip.transferTo(out);
          }
        }
      }
    }
  }

  /** Reads a text entry, or null when absent. */
  public static String readEntry(Path zipFile, String name) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals(name)) {
          return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    }
    return null;
  }
}
