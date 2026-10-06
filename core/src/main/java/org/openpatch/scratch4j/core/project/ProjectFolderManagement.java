package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Create, rename and recoverably delete ordinary project folders. */
public final class ProjectFolderManagement {

  private static final Set<String> PROTECTED = Set.of(
      ".git", ".scratch4j", "+libs", "target", "build", "assets");
  private static final Set<String> CODE_EXTENSIONS = Set.of(
      "java", "json", "xml", "tmx", "frag", "vert", "glsl", "txt", "css", "html");

  private ProjectFolderManagement() {}

  public static Path create(ScratchProject project, Path parent, String name)
      throws IOException {
    Path root = project.root().toRealPath();
    Path directory = parent.toRealPath();
    if (!directory.startsWith(root) || !Files.isDirectory(directory)
        || inaccessiblePath(root, directory)) {
      throw new IOException("Choose a writable project folder");
    }
    checkName(name);
    Path target = directory.resolve(name);
    if (Files.exists(target)) throw new IOException("Folder already exists: " + name);
    return Files.createDirectory(target);
  }

  public static Path rename(ScratchProject project, Path folder, String newName)
      throws IOException {
    Path source = validate(project, folder);
    checkName(newName);
    Path target = source.resolveSibling(newName);
    if (source.equals(target) || Files.exists(target)) {
      throw new IOException("Choose an unused folder name");
    }
    checkReferences(project, source);
    return Files.move(source, target);
  }

  /** Moves the entire folder to IDE trash, preserving all of its contents. */
  public static Path delete(ScratchProject project, Path folder) throws IOException {
    Path source = validate(project, folder);
    checkReferences(project, source);
    Path trash = project.root().toRealPath().resolve(".scratch4j/trash");
    Files.createDirectories(trash);
    Path target = trash.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID()
        + "-" + source.getFileName());
    return Files.move(source, target);
  }

  private static Path validate(ScratchProject project, Path folder) throws IOException {
    Path root = project.root().toRealPath();
    Path source = folder.toRealPath();
    if (!Files.isDirectory(source) || source.equals(root) || !source.startsWith(root)
        || protectedPath(root, source)) {
      throw new IOException("This project folder cannot be moved or deleted");
    }
    return source;
  }

  private static boolean protectedPath(Path root, Path path) {
    Path relative = root.relativize(path);
    if (relative.getNameCount() == 0) return false;
    String first = relative.getName(0).toString();
    if (PROTECTED.contains(first)) {
      // New folders inside assets are allowed; the standard asset roots are not movable.
      if (!first.equals("assets")) return true;
      return relative.getNameCount() == 1 || relative.getNameCount() == 2
          && Set.of("images", "sounds", "shaders", "fonts", "maps", "data")
              .contains(relative.getName(1).toString());
    }
    return false;
  }

  private static boolean inaccessiblePath(Path root, Path path) {
    Path relative = root.relativize(path);
    return relative.getNameCount() > 0 && Set.of(
        ".git", ".scratch4j", "+libs", "target", "build")
        .contains(relative.getName(0).toString());
  }

  private static void checkName(String name) throws IOException {
    if (name == null || !name.matches("[A-Za-z0-9_][A-Za-z0-9_ -]*")
        || name.equals(".") || name.equals("..")) {
      throw new IOException("Use letters, digits, spaces, hyphens or underscores");
    }
  }

  private static void checkReferences(ScratchProject project, Path folder)
      throws IOException {
    Path root = project.root().toRealPath();
    String prefix = root.relativize(folder).toString().replace('\\', '/');
    List<Path> contained;
    try (Stream<Path> walk = Files.walk(folder)) {
      contained = walk.filter(Files::isRegularFile).toList();
    }
    if (contained.stream().anyMatch(p -> p.getFileName().toString().endsWith(".java"))) {
      throw new IOException("Move or delete Java source files individually before changing this folder");
    }
    if (project.settings().splashLogo != null
        && project.settings().splashLogo.startsWith(prefix + "/")) {
      throw new IOException("The folder contains the project splash image");
    }
    // Check both files inside and outside the folder. A source in the folder can still
    // contain an absolute project-relative path that a move would break.
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path directory,
          BasicFileAttributes attributes) {
        if (!directory.equals(root) && Set.of(
            ".scratch4j", ".git", "target", "build", "+libs")
            .contains(directory.getFileName().toString())) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
          throws IOException {
        if (!attributes.isRegularFile() || attributes.size() > 2_000_000) {
          return FileVisitResult.CONTINUE;
        }
        String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || !CODE_EXTENSIONS.contains(filename.substring(dot + 1))) {
          return FileVisitResult.CONTINUE;
        }
        String text = Files.readString(file);
        if (text.contains(prefix + "/") || text.contains("\"" + prefix + "\"")) {
          throw new IOException("The folder path is referenced in " + root.relativize(file));
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }
}
