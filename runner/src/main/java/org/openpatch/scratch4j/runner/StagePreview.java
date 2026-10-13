package org.openpatch.scratch4j.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ScratchProject;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a stage looks like when the program starts: the project runs in the
 * library's preview mode ({@code -Dscratch4j.preview}), its stage takes one
 * step and draws one frame, and the program ends. The designer shows that
 * frame, so whatever the code builds - from a map, a loop, a save file -
 * appears as it will in the game, without the IDE having to guess.
 *
 * <p>The preview compiles into its own folder ({@code .scratch4j/build/preview})
 * so it never disturbs a program that is running.
 */
public final class StagePreview {

  /** A sprite of the previewed stage, as the library reports it. */
  public record Sprite(String type, String field, double x, double y, int width, int height,
      double direction, double size, String costume, boolean visible, boolean ui,
      boolean omitted) {}

  /**
   * A preview: the frame (stage size, without the sprites that were left out)
   * and where the camera and the sprites were. {@code error} explains a
   * missing preview (the code does not compile, no stage was shown...).
   */
  public record Result(Path frame, String stage, int width, int height, double cameraX,
      double cameraY, double zoom, List<Sprite> sprites, String error) {

    public boolean ok() {
      return error == null && frame != null;
    }

    static Result failed(String error) {
      return new Result(null, null, 0, 0, 0, 0, 100, List.of(), error);
    }
  }

  private StagePreview() {}

  /**
   * Previews {@code stageClass} of the project.
   *
   * @param startClass the class the program starts with (its {@code main})
   * @param stageClass the stage to show; null for the one the program shows first
   * @param omit fields of the stage whose sprites the designer draws itself
   */
  public static Result make(ScratchProject project, String startClass, String stageClass,
      Collection<String> omit, Path java, Duration timeout) {
    // one preview at a time: they share the compiled classes
    synchronized (StagePreview.class) {
      return makeNow(project, startClass, stageClass, omit, java, timeout);
    }
  }

  /** The folder the last preview of a stage is in (null: the program's first stage). */
  public static Path folder(ScratchProject project, String stageClass) {
    return project.root().resolve(".scratch4j/build/preview/out")
        .resolve(stageClass == null ? "start" : stageClass);
  }

  /** The last preview of a stage, if there is one with a picture (thumbnails). */
  public static Result last(ScratchProject project, String stageClass) {
    try {
      Result result = read(folder(project, stageClass));
      return result.ok() ? result : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static Result makeNow(ScratchProject project, String startClass, String stageClass,
      Collection<String> omit, Path java, Duration timeout) {
    List<String> hidden = hiddenDisplay();
    try {
      Path buildDir = project.root().resolve(".scratch4j/build/preview");
      Path outDir = buildDir.resolve("classes");
      Path launcherDir = buildDir.resolve("launcher");
      Path output = folder(project, stageClass);
      Files.createDirectories(output);
      Files.createDirectories(launcherDir);
      Path launcher = launcherDir.resolve("Scratch4JLauncher.java");
      Files.writeString(launcher, LauncherSource.generate(project.settings()),
          StandardCharsets.UTF_8);
      List<Path> sources = new ArrayList<>(project.javaSources());
      sources.add(launcher);
      List<Path> classpath = new ArrayList<>(project.libs());
      classpath.add(project.root());
      CompileResult compiled = new CompilerService().compile(sources, classpath, outDir);
      if (!compiled.success()) {
        return Result.failed("The code does not compile yet.");
      }
      Files.deleteIfExists(output.resolve("frame.png"));
      Files.deleteIfExists(output.resolve("stage.json"));

      List<String> command = new ArrayList<>(hidden);
      command.add(java.toString());
      command.addAll(RunConfig.DEFAULT_JVM_ARGS);
      command.add("-Dscratch4j.preview=" + output.toAbsolutePath());
      if (stageClass != null && !stageClass.equals(startClass)) {
        command.add("-Dscratch4j.preview.stage=" + stageClass);
      }
      if (omit != null && !omit.isEmpty()) {
        command.add("-Dscratch4j.preview.omit=" + String.join(",", omit));
      }
      command.add("-cp");
      List<String> entries = new ArrayList<>();
      entries.add(outDir.toString());
      entries.add(project.root().toString());
      for (Path jar : project.libs()) entries.add(jar.toString());
      command.add(String.join(System.getProperty("path.separator"), entries));
      command.add("Scratch4JLauncher");
      command.add(startClass);

      ProcessBuilder builder = GraphicsCompatibility.apply(new ProcessBuilder(command));
      builder.directory(project.root().toFile());
      builder.redirectErrorStream(true);
      builder.redirectOutput(buildDir.resolve("preview.log").toFile());
      if (!hidden.isEmpty()) {
        // only the invisible display: nothing may reach the desktop's
        builder.environment().remove("WAYLAND_DISPLAY");
      }
      Process process = builder.start();
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        // xvfb-run starts the X server and the program as children of its own
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        return Result.failed("The program took too long to show its stage.");
      }
      return read(output);
    } catch (IOException e) {
      return Result.failed("The preview could not run: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.failed("The preview was stopped.");
    }
  }

  /**
   * What the preview's program runs in so that its window is never seen: on
   * Linux an X server of its own that draws into memory ({@code xvfb-run}),
   * because tiling window managers and Wayland compositors place every new
   * window on the screen, wherever the program puts it. Elsewhere nothing
   * (the library moves its window out of sight). Without {@code xvfb-run}
   * the window may show for a moment, which is fine for a preview the user
   * asked for.
   */
  public static List<String> hiddenDisplay() {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    if (!os.contains("linux")) {
      return List.of();
    }
    Path xvfbRun = onPath("xvfb-run");
    if (xvfbRun == null) {
      // asked for with the Refresh button: a window for a moment is fine then
      return List.of();
    }
    // -a: the first free display number; a screen big enough for any window
    return List.of(xvfbRun.toString(), "-a", "-s", "-screen 0 1920x1200x24");
  }

  private static Path onPath(String program) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String dir : path.split(java.io.File.pathSeparator)) {
      if (dir.isEmpty()) continue;
      Path candidate = Path.of(dir, program);
      if (Files.isExecutable(candidate)) return candidate;
    }
    return null;
  }

  /** Reads what the library wrote into {@code folder}. */
  static Result read(Path folder) throws IOException {
    Path json = folder.resolve("stage.json");
    if (!Files.isRegularFile(json)) {
      return Result.failed("The program ended before it showed a stage.");
    }
    JsonNode root = JsonMapper.builder().build().readTree(Files.readString(json));
    if (root.has("error")) {
      return Result.failed(root.get("error").asString());
    }
    List<Sprite> sprites = new ArrayList<>();
    for (JsonNode s : root.path("sprites")) {
      sprites.add(new Sprite(simple(s.path("class").asString()),
          s.path("field").isNull() ? null : s.path("field").asString(),
          s.path("x").asDouble(), s.path("y").asDouble(), s.path("width").asInt(),
          s.path("height").asInt(), s.path("direction").asDouble(), s.path("size").asDouble(),
          s.path("costume").isNull() ? null : s.path("costume").asString(),
          s.path("visible").asBoolean(), s.path("ui").asBoolean(),
          s.path("omitted").asBoolean()));
    }
    Path frame = folder.resolve("frame.png");
    JsonNode camera = root.path("camera");
    return new Result(Files.isRegularFile(frame) ? frame : null,
        simple(root.path("stage").asString()), root.path("width").asInt(),
        root.path("height").asInt(), camera.path("x").asDouble(), camera.path("y").asDouble(),
        camera.path("zoom").asDouble(100), List.copyOf(sprites),
        Files.isRegularFile(frame) ? null : "The preview has no picture.");
  }

  /** {@code demos.tiled.Player} -> {@code Player}: project classes are named simply. */
  private static String simple(String name) {
    return name == null ? null : name.substring(name.lastIndexOf('.') + 1);
  }
}
