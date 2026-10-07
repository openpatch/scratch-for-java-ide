package org.openpatch.scratch4j.core.project;

import tools.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.openpatch.scratch4j.core.io.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * IDE-only metadata stored in {@code .scratch4j/project.json}. A project must
 * run without this file; defaults are derived from the sources.
 *
 * <pre>
 * { "version": 1, "startStage": "MyStage", "flavour": "standard" }
 * </pre>
 */
public final class ProjectSettings {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  public int version = 1;
  public int portableVersion = 1;
  public String startFile = "";
  public String libraryVersion = "";
  public String sourceEnvironment = "studio";
  public String lesson = "";
  public String checkpoint = "";
  public java.util.List<String> browserFeatures = new java.util.ArrayList<>();
  public java.util.List<String> externalDependencies = new java.util.ArrayList<>();
  /** Course-provided Java classes replaced by the browser's NRW library, retained as files. */
  public java.util.List<String> desktopFiles = new java.util.ArrayList<>();
  private final java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();

  @JsonAnySetter
  public void extra(String name, Object value) { extra.put(name, value); }

  @JsonAnyGetter
  public java.util.Map<String, Object> extra() { return extra; }
  /** Simple class name of the stage Run and Export use; empty = auto-detect. */
  public String startStage = "";
  public String flavour = LibraryFlavour.STANDARD.id();
  public boolean fullScreen;
  public boolean pixelArt;
  public boolean debugOnStart;
  /** Project-relative image path used as the launch splash; empty = library default. */
  public String splashLogo = "";
  /**
   * The library version the user chose to keep ({@code 4.22.0}, {@code 5.5.0-nrw}):
   * the IDE then stops offering its own jar. Empty = not pinned.
   */
  public String libraryPin = "";
  /** Version written into exported apps (Info.plist, VERSION.txt, menu entry). */
  public String appVersion = "1.0";
  /** Project-relative image for the exported app's icon; empty = splash or first costume. */
  public String appIcon = "";

  /** Reads the settings of the project at {@code projectRoot}; defaults if absent. */
  public static ProjectSettings load(Path projectRoot) {
    Path file = projectRoot.resolve(".scratch4j/project.json");
    if (Files.isRegularFile(file)) {
      try {
        ProjectSettings s = MAPPER.readValue(file.toFile(), ProjectSettings.class);
        if (s != null) {
          if (s.version == 0) {
            s.version = 1;
          }
          if (s.startStage == null) {
            s.startStage = "";
          }
          if (s.flavour == null) {
            s.flavour = LibraryFlavour.STANDARD.id();
          }
          if (s.splashLogo == null) {
            s.splashLogo = "";
          }
          if (s.libraryPin == null) {
            s.libraryPin = "";
          }
          if (s.appVersion == null || s.appVersion.isBlank()) {
            s.appVersion = "1.0";
          }
          if (s.appIcon == null) {
            s.appIcon = "";
          }
          return s;
        }
      } catch (RuntimeException e) {
        // unreadable metadata must never block a project from opening
      }
    }
    return new ProjectSettings();
  }

  /** Writes the settings to {@code .scratch4j/project.json}, creating the folder. */
  public void save(Path projectRoot) throws IOException {
    Path dir = projectRoot.resolve(".scratch4j");
    Files.createDirectories(dir);
    AtomicFiles.writeString(dir.resolve("project.json"),
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this));
  }

  public LibraryFlavour flavourEnum() {
    return LibraryFlavour.fromId(flavour);
  }
}
