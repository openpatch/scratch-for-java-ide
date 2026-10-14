package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
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

  // Class discovery uses a light regex scan: every "class Name extends Super", followed
  // through the project's own classes, so World extends Level extends Stage is a stage too.
  private static final Pattern CLASS_EXTENDS =
      Pattern.compile("\\bclass\\s+(\\w+)\\s*(?:<[^>{]*>)?\\s+extends\\s+(?:\\w+\\.)*(\\w+)");
  private static final Set<String> STAGE_ROOTS = Set.of("Stage");
  private static final Set<String> WINDOW_ROOTS = Set.of("Window");
  private static final Set<String> SPRITE_ROOTS = Set.of("AnimatedSprite", "UISprite", "Sprite");
  /** The stage a start class with {@code main} shows: {@code setStage(new Level1())}. */
  private static final Pattern SET_STAGE =
      Pattern.compile("\\bsetStage\\s*\\(\\s*new\\s+(\\w+)\\s*\\(");

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

  /** Simple names of the project's {@code Stage} subclasses, direct or not, sorted. */
  public List<String> stageClasses() throws IOException {
    return subclassesOf(STAGE_ROOTS);
  }

  /** Simple names of the project's {@code Window} subclasses, direct or not, sorted. */
  public List<String> windowClasses() throws IOException {
    return subclassesOf(WINDOW_ROOTS);
  }

  /** Simple names of the project's Sprite subclasses, direct or not, sorted. */
  public List<String> spriteClasses() throws IOException {
    return subclassesOf(SPRITE_ROOTS);
  }

  /** Classes whose chain of superclasses within the project ends in one of {@code roots}. */
  private List<String> subclassesOf(Set<String> roots) throws IOException {
    Map<String, String> superclass = new HashMap<>();
    for (Path source : javaSources()) {
      Matcher m = CLASS_EXTENDS.matcher(Files.readString(source, StandardCharsets.UTF_8));
      while (m.find()) {
        superclass.putIfAbsent(m.group(1), m.group(2));
      }
    }
    List<String> found = new ArrayList<>();
    for (String name : superclass.keySet()) {
      String next = superclass.get(name);
      // a cycle does not compile, but must not hang the scan either
      for (int depth = 0; next != null && depth < superclass.size(); depth++) {
        if (roots.contains(next)) {
          found.add(name);
          break;
        }
        next = superclass.get(next);
      }
    }
    found.sort(Comparator.naturalOrder());
    return found;
  }

  /**
   * The class Run and Export start: the one from {@code .scratch4j/project.json}
   * if it names a real stage or {@code Window} subclass or a class with
   * {@code main}, otherwise a {@code Window} subclass (it creates the window and
   * picks the first stage, like the tutorials' {@code DodgeWindow}), otherwise a
   * class with {@code main}, otherwise the first stage. A saved stage gives way to
   * the {@code main} that shows it, which sets up the window first. Empty when the
   * project has none of these.
   */
  public String startStage() throws IOException {
    List<String> stages = stageClasses();
    List<String> windows = windowClasses();
    List<String> mains = mainClasses();
    String chosen = settings.startStage;
    if (!chosen.isBlank() && stages.contains(chosen) && windows.isEmpty()) {
      for (String main : mains) {
        ShownStage shown = stageShownBy(main);
        if (!main.equals(chosen) && shown != null && shown.name().equals(chosen)) {
          return main;
        }
      }
    }
    if (!chosen.isBlank() && (stages.contains(chosen) || windows.contains(chosen)
        || mains.contains(chosen))) {
      return chosen;
    }
    if (!windows.isEmpty()) {
      return windows.get(0);
    }
    if (!mains.isEmpty()) {
      return mains.get(0);
    }
    return stages.isEmpty() ? "" : stages.get(0);
  }

  /** Classes whose {@code Name.java} declares a {@code main}: {@code Main} first, then by name. */
  private List<String> mainClasses() throws IOException {
    List<String> found = new ArrayList<>();
    for (Path source : javaSources()) {
      if (MAIN.matcher(Files.readString(source, StandardCharsets.UTF_8)).find()) {
        found.add(source.getFileName().toString().replaceFirst("\\.java$", ""));
      }
    }
    found.sort(Comparator.comparing((String name) -> !name.equals("Main"))
        .thenComparing(Comparator.naturalOrder()));
    return found;
  }

  private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(");

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
   * otherwise the start class when it is a stage, otherwise the stage a start
   * class with {@code main} passes to {@code setStage}, otherwise the first stage.
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
    if (stages.contains(start)) {
      return start;
    }
    ShownStage shown = stageShownBy(start);
    if (shown != null && stages.contains(shown.name())) {
      return shown.name();
    }
    return stages.isEmpty() ? "" : stages.get(0);
  }

  /** The one {@code setStage(new X(...))} in a start class's source. */
  private record ShownStage(Path file, String source, MatchResult match) {
    String name() {
      return match.group(1);
    }
  }

  /** The start class's only {@code setStage(new X(...))}, or null when it has none or several. */
  private ShownStage stageShownBy(String startClass) throws IOException {
    Path file = startClass.isBlank() ? null : sourceOf(startClass);
    if (file == null) {
      return null;
    }
    String source = Files.readString(file, StandardCharsets.UTF_8);
    List<MatchResult> found = SET_STAGE.matcher(source).results().toList();
    return found.size() == 1 ? new ShownStage(file, source, found.get(0)) : null;
  }

  /**
   * Makes {@code stageClass} the first stage: the managed window class's
   * {@code setStage} line (the program's start stays the window), the
   * {@code setStage} line of a start class with {@code main} (it keeps its window
   * settings), or else the start class in {@code .scratch4j/project.json}.
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
    String start = startStage();
    ShownStage shown = stageClasses().contains(start) ? null : stageShownBy(start);
    if (shown != null) {
      MatchResult m = shown.match();
      org.openpatch.scratch4j.core.io.LocalHistory.writeString(root, shown.file(),
          shown.source().substring(0, m.start(1)) + stageClass + shown.source().substring(m.end(1)));
      if (!settings.startStage.equals(start)) {
        // a saved stage would win again once main shows another one
        settings.startStage = start;
        settings.save(root);
      }
      return;
    }
    settings.startStage = stageClass;
    settings.save(root);
  }
}
