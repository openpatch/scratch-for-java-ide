package org.openpatch.scratch4j.export;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import org.openpatch.scratch4j.core.project.PortableProject;
import org.openpatch.scratch4j.core.project.ScratchProject;

/** Validated offline course packs, published only after all project folders are ready. */
public final class CoursePack {
  private CoursePack() {}
  public record Imported(Path root, List<Path> projects) {}

  public static Imported importZip(Path archive, Path parent, Map<String, Path> libraries) throws IOException {
    String name = PortableProject.path(archive.getFileName().toString().replaceFirst("(?i)\\.zip$", ""));
    Path root = parent.toAbsolutePath().resolve(name);
    if (Files.exists(root)) throw new IOException("Folder already exists: " + root);
    Files.createDirectories(parent);
    Path staging = Files.createTempDirectory(parent, ".scratch4j-course-");
    try {
      Path unpacked = ProjectFormats.importZip(archive, staging);
      Path manifestFile = unpacked.resolve(".scratch4j/course.json");
      if (!Files.isRegularFile(manifestFile)) throw new IOException("This ZIP has no course manifest");
      var manifest = tools.jackson.databind.json.JsonMapper.builder().build().readTree(manifestFile.toFile());
      if (manifest.path("schemaVersion").asInt() != 1 || manifest.path("libraryVersion").asText().isBlank()
          || !manifest.path("projects").isArray()
          || manifest.path("projects").isEmpty() || manifest.path("projects").size() > 100) {
        throw new IOException("Unsupported or empty course manifest");
      }
      List<String> paths = new ArrayList<>();
      HashSet<String> unique = new HashSet<>();
      for (var entry : manifest.path("projects")) {
        String path = PortableProject.path(entry.path("path").asText());
        if (!path.startsWith("projects/") || !unique.add(path.toLowerCase(java.util.Locale.ROOT))) {
          throw new IOException("Invalid course project: " + path);
        }
        for (String previous : paths) if (previous.startsWith(path + "/") || path.startsWith(previous + "/")) {
          throw new IOException("Course project folders overlap: " + path);
        }
        Path directory = unpacked.resolve(path);
        if (!Files.isDirectory(directory)) throw new IOException("Missing course project: " + path);
        ScratchProject project = ScratchProject.open(directory);
        if (project.javaSources().isEmpty()) throw new IOException("Course project contains no Java: " + path);
        String flavour = project.settings().flavour;
        if (!project.settings().libraryVersion.equals(manifest.path("libraryVersion").asText())) {
          throw new IOException("Course library version mismatch: " + path);
        }
        if (!flavour.equals(entry.path("flavour").asText())) throw new IOException("Course flavor mismatch: " + path);
        Path library = libraries.get(flavour);
        if (library == null || !Files.isRegularFile(library)) throw new IOException("Prepare the bundled " + flavour + " library before importing");
        Files.createDirectories(project.libsDir());
        Files.copy(library, project.libsDir().resolve(library.getFileName()));
        if (!project.settings().libraryVersion.equals(org.openpatch.scratch4j.core.project.LibraryCheck.projectVersion(project))) {
          throw new IOException("Bundled library does not match course version " + project.settings().libraryVersion);
        }
        paths.add(path);
      }
      Files.move(unpacked, root);
      return new Imported(root, paths.stream().map(root::resolve).toList());
    } catch (RuntimeException e) { throw new IOException("Invalid course pack: " + e.getMessage(), e); }
    finally { if (Files.exists(staging)) PortableProject.deleteTree(staging); }
  }
}
