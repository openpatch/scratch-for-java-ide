package org.openpatch.scratch4j.core.project;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The finished tutorial projects and library demos shipped inside the IDE
 * (offline). They are imported from the library repo by
 * {@code scripts/sync-templates.py} into the core resources
 * ({@code org/openpatch/scratch4j/core/templates/}) with an {@code index.json}
 * listing every file.
 */
public final class BundledTemplates {

  /** One bundled project. {@code kind} is {@code tutorial} or {@code demo}. */
  public record Template(String id, String kind, String title, String startClass,
      List<String> files) {
    public boolean isTutorial() {
      return "tutorial".equals(kind);
    }
  }

  private static final String BASE = "/org/openpatch/scratch4j/core/templates/";
  private static List<Template> all;

  private BundledTemplates() {}

  /** Every bundled template, tutorials first, in the index order. */
  public static synchronized List<Template> list() {
    if (all == null) {
      try (InputStream in = resource("index.json")) {
        all = List.of(JsonMapper.builder().build().readValue(in, Template[].class));
      } catch (IOException e) {
        throw new UncheckedIOException("Bundled template index missing", e);
      }
    }
    return all;
  }

  public static Template get(String id) {
    return list().stream().filter(t -> t.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown template " + id));
  }

  /**
   * Creates {@code parentDir/name} as a copy of the template, with the library
   * jar in {@code +libs}, the standard asset folders and the start class
   * recorded in {@code .scratch4j/project.json}.
   */
  public static Path create(String id, Path parentDir, String name, Path libraryJar)
      throws IOException {
    Template template = get(id);
    Path root = parentDir.resolve(name);
    if (Files.exists(root)) {
      throw new IOException("Folder already exists: " + root);
    }
    Files.createDirectories(root);
    for (String file : template.files()) {
      Path target = root.resolve(file).normalize();
      if (!target.startsWith(root)) {
        throw new IOException("Template file outside the project: " + file);
      }
      Files.createDirectories(target.getParent());
      try (InputStream in = resource(id + "/" + file)) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    // the library's examples are written by hand: give them the visual editors' regions
    org.openpatch.scratch4j.core.region.DesignerRegions.ensureAll(root);
    Path libs = root.resolve("+libs");
    Files.createDirectories(libs);
    if (libraryJar != null) {
      Files.copy(libraryJar, libs.resolve(libraryJar.getFileName()),
          StandardCopyOption.REPLACE_EXISTING);
    }
    Files.createDirectories(root.resolve("assets/images"));
    Files.createDirectories(root.resolve("assets/sounds"));
    ProjectSettings settings = new ProjectSettings();
    settings.startStage = template.startClass();
    settings.save(root);
    return root;
  }

  private static InputStream resource(String path) throws IOException {
    InputStream in = BundledTemplates.class.getResourceAsStream(BASE + path);
    if (in == null) {
      throw new IOException("Missing bundled template resource " + path);
    }
    return in;
  }
}
