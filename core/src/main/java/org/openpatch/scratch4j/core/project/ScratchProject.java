package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A Scratch for Java student project: a plain folder of Java classes that
 * opens in BlueJ / VS Code with nothing but the jar. The IDE adds only the
 * optional {@code .scratch4j/} metadata folder.
 */
public final class ScratchProject {

  /** Directories never treated as project sources. */
  private static final List<String> EXCLUDED_DIRS =
      List.of(".scratch4j", "+libs", "build", "target", ".git", ".vscode", "assets", "export");

  // Class discovery uses a light regex scan for the shapes used by the templates.
  private static final Pattern STAGE_CLASS =
      Pattern.compile("\\bclass\\s+(\\w+)\\s+extends\\s+Stage\\b");
  private static final Pattern WINDOW_CLASS =
      Pattern.compile("\\bclass\\s+(\\w+)\\s+extends\\s+Window\\b");
  private static final Pattern SPRITE_CLASS =
      Pattern.compile("\\bclass\\s+(\\w+)\\s+extends\\s+(?:AnimatedSprite|UISprite|Sprite)\\b");

  private final Path root;
  private final String name;
  private final ProjectLayout layout;
  private final ProjectSettings settings;

  private ScratchProject(Path root, ProjectLayout layout, ProjectSettings settings) {
    this.root = root;
    this.name = root.getFileName().toString();
    this.layout = layout;
    this.settings = settings;
  }

  /** Opens the project folder. {@code root} must exist. */
  public static ScratchProject open(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      throw new IOException("Not a project folder: " + root);
    }
    ProjectLayout layout = detectLayout(root);
    ProjectSettings settings = ProjectSettings.load(root);
    return new ScratchProject(root, layout, settings);
  }

  private static ProjectLayout detectLayout(Path root) {
    if (Files.isRegularFile(root.resolve("package.bluej"))) {
      return ProjectLayout.BLUEJ;
    }
    if (Files.isRegularFile(root.resolve(".vscode/settings.json"))) {
      return ProjectLayout.VSCODE;
    }
    return ProjectLayout.PLAIN;
  }

  public Path root() {
    return root;
  }

  public String name() {
    return name;
  }

  public ProjectLayout layout() {
    return layout;
  }

  public ProjectSettings settings() {
    return settings;
  }

  /** Folder that holds the library jar ({@code +libs}). */
  public Path libsDir() {
    return root.resolve("+libs");
  }

  /** The jars in {@code +libs} (sorted). */
  public List<Path> libs() throws IOException {
    if (!Files.isDirectory(libsDir())) {
      return List.of();
    }
    try (Stream<Path> s = Files.list(libsDir())) {
      return s.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList();
    }
  }

  /** All {@code .java} source files of the project, excluding generated/build dirs. */
  public List<Path> javaSources() throws IOException {
    List<Path> sources = new ArrayList<>();
    collectSources(root, sources);
    sources.sort(Comparator.comparing(Path::toString));
    return sources;
  }

  private static void collectSources(Path dir, List<Path> sources) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      for (Path p : entries.sorted().toList()) {
        String fileName = p.getFileName().toString();
        if (Files.isDirectory(p)) {
          if (!EXCLUDED_DIRS.contains(fileName) && !fileName.startsWith(".")) {
            collectSources(p, sources);
          }
        } else if (fileName.endsWith(".java")) {
          sources.add(p);
        }
      }
    }
  }

  /** Simple names of the project's {@code Stage} subclasses, sorted. */
  public List<String> stageClasses() throws IOException {
    return scan(STAGE_CLASS);
  }

  /** Simple names of the project's {@code Window} subclasses, sorted. */
  public List<String> windowClasses() throws IOException {
    return scan(WINDOW_CLASS);
  }

  /** Simple names of the project's Sprite subclasses, sorted. */
  public List<String> spriteClasses() throws IOException {
    return scan(SPRITE_CLASS);
  }

  private List<String> scan(Pattern pattern) throws IOException {
    List<String> found = new ArrayList<>();
    for (Path source : javaSources()) {
      String text = Files.readString(source, StandardCharsets.UTF_8);
      Matcher m = pattern.matcher(text);
      while (m.find()) {
        if (!found.contains(m.group(1))) {
          found.add(m.group(1));
        }
      }
    }
    found.sort(Comparator.naturalOrder());
    return found;
  }

  /**
   * The class Run and Export start: the one from {@code .scratch4j/project.json}
   * if it names a real stage or {@code Window} subclass, otherwise a
   * {@code Window} subclass (it creates the window and picks the first stage,
   * like the tutorials' {@code DodgeWindow}), otherwise the first stage. Empty
   * when the project has neither.
   */
  public String startStage() throws IOException {
    List<String> stages = stageClasses();
    List<String> windows = windowClasses();
    String chosen = settings.startStage;
    if (!chosen.isBlank() && (stages.contains(chosen) || windows.contains(chosen)
        || hasMain(chosen))) {
      return chosen;
    }
    if (!windows.isEmpty()) {
      return windows.get(0);
    }
    return stages.isEmpty() ? "" : stages.get(0);
  }

  /** Whether {@code ClassName.java} declares a {@code static void main}. */
  private boolean hasMain(String className) throws IOException {
    for (Path source : javaSources()) {
      if (source.getFileName().toString().equals(className + ".java")) {
        return Pattern.compile("\\bvoid\\s+main\\s*\\(")
            .matcher(Files.readString(source, StandardCharsets.UTF_8)).find();
      }
    }
    return false;
  }

  /**
   * The project's window class whose managed regions hold the global settings
   * and the first stage ({@link org.openpatch.scratch4j.core.region.WindowDocument}),
   * or null.
   */
  public String windowClass() throws IOException {
    for (String name : windowClasses()) {
      Path file = sourceOf(name);
      if (file != null && org.openpatch.scratch4j.core.region.WindowDocument.isManaged(
          Files.readString(file, StandardCharsets.UTF_8))) {
        return name;
      }
    }
    return null;
  }

  /** The source file {@code Name.java}, or null. */
  public Path sourceOf(String className) throws IOException {
    for (Path source : javaSources()) {
      if (source.getFileName().toString().equals(className + ".java")) {
        return source;
      }
    }
    return null;
  }

  /**
   * The stage the program shows first: the managed window's {@code setStage},
   * otherwise the start class when it is a stage, otherwise the first stage.
   */
  public String firstStage() throws IOException {
    String window = windowClass();
    if (window != null) {
      try {
        String stage = org.openpatch.scratch4j.core.region.WindowDocument.read(
            Files.readString(sourceOf(window), StandardCharsets.UTF_8)).startStage();
        if (stage != null && stageClasses().contains(stage)) {
          return stage;
        }
      } catch (RuntimeException ignored) {
        // hand-written window regions: fall back below
      }
    }
    String start = startStage();
    List<String> stages = stageClasses();
    return stages.contains(start) ? start : stages.isEmpty() ? "" : stages.get(0);
  }

  /**
   * Makes {@code stageClass} the first stage: the managed window class's
   * {@code setStage} line (the program's start stays the window), or the start
   * class in {@code .scratch4j/project.json} when there is no such window.
   */
  public void setStartStage(String stageClass) throws IOException {
    String window = windowClass();
    if (window != null) {
      Path file = sourceOf(window);
      String source = Files.readString(file, StandardCharsets.UTF_8);
      var settings = org.openpatch.scratch4j.core.region.WindowDocument.read(source);
      org.openpatch.scratch4j.core.io.LocalHistory.writeString(root, file,
          org.openpatch.scratch4j.core.region.WindowDocument.write(source,
              settings.withStartStage(stageClass)));
      return;
    }
    settings.startStage = stageClass;
    settings.save(root);
  }
}
