package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Moving, renaming, duplicating and recoverable deletion of individual project
 * files; never deletes directories.
 */
public final class ProjectFileManagement {

  /** Top-level folders files cannot be moved into or out of. */
  private static final Set<String> CLOSED = Set.of(".scratch4j", ".git", "target", "build",
      "+libs", "export", ".vscode");

  private ProjectFileManagement() {}

  /**
   * Moves a file into another project folder and rewrites the Java string
   * literals that name it. Java classes stay in the project folder: BlueJ and
   * VS Code look for them there, and a class in a subfolder would need a package.
   */
  public static Path move(ScratchProject project, Path file, Path folder) throws IOException {
    return move(project, file, folder, FileUsages.Mode.STRICT);
  }

  /** {@link #move(ScratchProject, Path, Path)}, choosing what happens to the file's usages. */
  public static Path move(ScratchProject project, Path file, Path folder,
      FileUsages.Mode mode) throws IOException {
    return move(project, file, folder, mode, java.util.Set.of());
  }

  /** Like the method above, leaving the {@code skipped} usages (turned off in the preview). */
  public static Path move(ScratchProject project, Path file, Path folder,
      FileUsages.Mode mode, java.util.Set<FileUsages.Usage> skipped) throws IOException {
    Path root = project.root().toRealPath();
    Path source = movable(project, file);
    Path directory = folder.toRealPath();
    if (!Files.isDirectory(directory) || !directory.startsWith(root)
        || closed(root, directory)) {
      throw new IOException("Choose a folder of this project");
    }
    if (directory.equals(source.getParent())) {
      throw new IOException(source.getFileName() + " is already in this folder");
    }
    Path target = directory.resolve(source.getFileName());
    if (Files.exists(target)) {
      throw new IOException("The folder already contains " + source.getFileName());
    }
    if (source.getFileName().toString().endsWith(".java")) {
      if (!directory.equals(root)) {
        throw new IOException("Java classes stay in the project folder, where BlueJ and "
            + "VS Code find them");
      }
      // back to the project folder: the default package needs no other change
      return Files.move(source, target);
    }
    return ProjectAssetManagement.relocate(project, source, target, mode, skipped);
  }

  /**
   * Renames a file that is not a Java class (those are renamed with their
   * class) and rewrites the Java string literals that name it.
   */
  public static Path rename(ScratchProject project, Path file, String newName)
      throws IOException {
    return rename(project, file, newName, FileUsages.Mode.STRICT);
  }

  /** {@link #rename(ScratchProject, Path, String)}, choosing what happens to the usages. */
  public static Path rename(ScratchProject project, Path file, String newName,
      FileUsages.Mode mode) throws IOException {
    return rename(project, file, newName, mode, java.util.Set.of());
  }

  /** Like the method above, leaving the {@code skipped} usages (turned off in the preview). */
  public static Path rename(ScratchProject project, Path file, String newName,
      FileUsages.Mode mode, java.util.Set<FileUsages.Usage> skipped) throws IOException {
    Path source = movable(project, file);
    if (source.getFileName().toString().endsWith(".java")) {
      throw new IOException("Rename a Java file by renaming its class");
    }
    checkFileName(newName);
    if (newName.toLowerCase(Locale.ROOT).endsWith(".java")) {
      throw new IOException("A file cannot become a Java class by renaming it");
    }
    Path target = source.resolveSibling(newName);
    if (source.equals(target) || Files.exists(target)) {
      throw new IOException("Choose an unused file name");
    }
    return ProjectAssetManagement.relocate(project, source, target, mode, skipped);
  }

  /**
   * Copies a file that is not a Java class next to itself under
   * {@code newName} (Java classes duplicate through
   * {@link StageManagement#duplicateClass}).
   */
  public static Path duplicate(ScratchProject project, Path file, String newName)
      throws IOException {
    Path source = movable(project, file);
    if (source.getFileName().toString().endsWith(".java")) {
      throw new IOException("Duplicate a Java file by duplicating its class");
    }
    checkFileName(newName);
    Path target = source.resolveSibling(newName);
    if (Files.exists(target)) {
      throw new IOException("Choose an unused file name");
    }
    return Files.copy(source, target);
  }

  /** {@code cat.png} becomes {@code cat-2.png} (or -3, ... if that is taken). */
  public static String copyName(Path file) {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String base = dot <= 0 ? name : name.substring(0, dot);
    String extension = dot <= 0 ? "" : name.substring(dot);
    for (int i = 2; ; i++) {
      String candidate = base + "-" + i + extension;
      if (!Files.exists(file.resolveSibling(candidate))) {
        return candidate;
      }
    }
  }

  /**
   * Paste: copies a file or folder (of this project or from outside it) into
   * {@code folder}, as {@code cat-2.png} when the name is taken there. A class
   * of the project is duplicated as {@code Name2}; Java files only go into the
   * project folder, so a folder with Java files in it is not copied.
   */
  public static Path copyInto(ScratchProject project, Path source, Path folder)
      throws IOException {
    Path root = project.root().toRealPath();
    Path directory = folder.toRealPath();
    Path from = source.toRealPath();
    if (!Files.isDirectory(directory) || !directory.startsWith(root)
        || closed(root, directory)) {
      throw new IOException("Choose a folder of this project");
    }
    String name = from.getFileName().toString();
    if (Files.isDirectory(from)) {
      if (directory.startsWith(from)) {
        throw new IOException("A folder cannot be pasted into itself");
      }
      try (var walk = Files.walk(from)) {
        if (walk.anyMatch(p -> p.getFileName().toString().endsWith(".java"))) {
          throw new IOException(name + " contains Java classes, which stay in the "
              + "project folder");
        }
      }
      Path target = directory.resolve(freeName(directory, name));
      try (var walk = Files.walk(from)) {
        for (Path p : (Iterable<Path>) walk::iterator) {
          Files.copy(p, target.resolve(from.relativize(p).toString()));
        }
      }
      return target;
    }
    if (!Files.isRegularFile(from)) {
      throw new IOException("Choose a file or folder: " + name);
    }
    if (name.endsWith(".java")) {
      if (!directory.equals(root)) {
        throw new IOException("Java classes stay in the project folder, where BlueJ and "
            + "VS Code find them");
      }
      String className = name.substring(0, name.length() - 5);
      if (from.getParent().equals(root)) {
        int i = 2;
        while (Files.exists(root.resolve(className + i + ".java"))) i++;
        return StageManagement.duplicateClass(project, className, className + i);
      }
      if (Files.exists(root.resolve(name))) {
        throw new IOException("The project already has a class " + className);
      }
    }
    return Files.copy(from, directory.resolve(freeName(directory, name)));
  }

  /** {@code name}, or {@code name-2} (-3, ...) when {@code folder} already has it. */
  private static String freeName(Path folder, String name) {
    return Files.exists(folder.resolve(name)) ? copyName(folder.resolve(name)) : name;
  }

  /**
   * Undoes a delete: moves {@code trashed} (as a delete returned it) back to
   * {@code original}, which must still be free.
   */
  public static Path restore(ScratchProject project, Path trashed, Path original)
      throws IOException {
    Path root = project.root().toRealPath();
    Path from = trashed.toRealPath();
    Path target = original.toAbsolutePath().normalize();
    if (!from.startsWith(root.resolve(".scratch4j/trash"))) {
      throw new IOException("Not in the project trash: " + trashed.getFileName());
    }
    if (!target.startsWith(root) && !target.startsWith(project.root().toAbsolutePath()
        .normalize())) {
      throw new IOException("Not a path of this project: " + original);
    }
    if (Files.exists(target)) {
      throw new IOException(target.getFileName() + " exists again");
    }
    Files.createDirectories(target.getParent());
    return Files.move(from, target);
  }

  private static Path movable(ScratchProject project, Path file) throws IOException {
    Path root = project.root().toRealPath();
    Path source = file.toRealPath();
    if (!source.startsWith(root) || !Files.isRegularFile(source) || closed(root, source)) {
      throw new IOException("Choose a regular project file");
    }
    if (source.getParent().equals(root)
        && source.getFileName().toString().equals("package.bluej")) {
      throw new IOException("BlueJ needs package.bluej in the project folder");
    }
    String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".tmx") || name.endsWith(".tsx")) {
      throw new IOException("Maps name their tilesets relative to themselves; "
          + "move them together in Tiled");
    }
    return source;
  }

  private static boolean closed(Path root, Path path) {
    Path relative = root.relativize(path);
    return relative.getNameCount() > 0 && !relative.toString().isEmpty()
        && CLOSED.contains(relative.getName(0).toString());
  }

  private static void checkFileName(String name) throws IOException {
    if (name == null || !name.matches("[A-Za-z0-9_][A-Za-z0-9_ .-]*")
        || name.endsWith(".") || name.contains("..")) {
      throw new IOException("Use letters, digits, spaces, dots, hyphens or underscores");
    }
  }

  public static Path delete(ScratchProject project, Path file) throws IOException {
    return delete(project, file, false);
  }

  /**
   * Moves a file to IDE trash. Without {@code force} a file that is still used
   * stays; with it the code that uses it is left as it is (the problems list
   * shows what broke). The bundled library and IDE folders are never deleted.
   */
  public static Path delete(ScratchProject project, Path file, boolean force)
      throws IOException {
    Path root = project.root().toRealPath();
    Path source = file.toRealPath();
    if (!source.startsWith(root) || !Files.isRegularFile(source)
        || source.startsWith(root.resolve(".scratch4j"))
        || source.startsWith(root.resolve(".git"))
        || source.startsWith(root.resolve("target"))
        || source.startsWith(root.resolve("build"))) {
      throw new IOException("Choose a regular project file");
    }
    if (source.startsWith(root.resolve("assets"))) {
      return ProjectAssetManagement.delete(project, source, force);
    }
    if (source.startsWith(root.resolve("+libs"))) {
      throw new IOException("The bundled library cannot be deleted from the IDE");
    }
    String filename = source.getFileName().toString();
    if (filename.endsWith(".java")) {
      String className = filename.substring(0, filename.length() - 5);
      if (source.equals(root.resolve(className + ".java"))
          && project.stageClasses().contains(className)) {
        return StageManagement.delete(project, className, force);
      }
      if (force) {
        return trash(root, source);
      }
      Pattern token = Pattern.compile("\\b" + Pattern.quote(className) + "\\b");
      for (Path other : project.javaSources()) {
        if (!other.toRealPath().equals(source)
            && token.matcher(Files.readString(other)).find()) {
          throw new IOException("The class is still mentioned in "
              + root.relativize(other.toRealPath()));
        }
      }
    } else if (!force) {
      String relative = root.relativize(source).toString().replace('\\', '/');
      for (Path other : project.javaSources()) {
        if (Files.readString(other).contains(relative)) {
          throw new IOException("The file is still mentioned in "
              + root.relativize(other.toRealPath()));
        }
      }
    }
    return trash(root, source);
  }

  private static Path trash(Path root, Path source) throws IOException {
    Path trash = root.resolve(".scratch4j/trash");
    Files.createDirectories(trash);
    Path target = trash.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID()
        + "-" + source.getFileName());
    return Files.move(source, target);
  }
}
