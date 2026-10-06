package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** Recoverable deletion for individual project files; never deletes directories. */
public final class ProjectFileManagement {

  private ProjectFileManagement() {}

  public static Path delete(ScratchProject project, Path file) throws IOException {
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
      return ProjectAssetManagement.delete(project, source);
    }
    if (source.startsWith(root.resolve("+libs"))) {
      throw new IOException("The bundled library cannot be deleted from the IDE");
    }
    String filename = source.getFileName().toString();
    if (filename.endsWith(".java")) {
      String className = filename.substring(0, filename.length() - 5);
      if (source.equals(root.resolve(className + ".java"))
          && project.stageClasses().contains(className)) {
        return StageManagement.delete(project, className);
      }
      Pattern token = Pattern.compile("\\b" + Pattern.quote(className) + "\\b");
      for (Path other : project.javaSources()) {
        if (!other.toRealPath().equals(source)
            && token.matcher(Files.readString(other)).find()) {
          throw new IOException("The class is still mentioned in "
              + root.relativize(other.toRealPath()));
        }
      }
    } else {
      String relative = root.relativize(source).toString().replace('\\', '/');
      for (Path other : project.javaSources()) {
        if (Files.readString(other).contains(relative)) {
          throw new IOException("The file is still mentioned in "
              + root.relativize(other.toRealPath()));
        }
      }
    }
    Path trash = root.resolve(".scratch4j/trash");
    Files.createDirectories(trash);
    Path target = trash.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID()
        + "-" + filename);
    return Files.move(source, target);
  }
}
