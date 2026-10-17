package org.openpatch.scratch4j.runner;

import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Compiles a project (in-process javac) and runs the start stage in a fresh
 * JVM. The IDE process never touches {@code Window}; a crash, {@code System.exit}
 * or an endless loop in the student's code cannot take the IDE down.
 *
 * <p>Run layout (mirrors the library's {@code scripts/run.sh}):
 * the launcher is generated into {@code .scratch4j/build/launcher}, everything
 * compiles into {@code .scratch4j/build/classes}, and the program runs with
 * the project root as working directory so its own {@code assets/} folder is
 * found both as a classpath entry and as a working-directory path.
 */
public final class ProjectRunner {

  /** Compiles the project (with the generated launcher) and starts it in a fresh JVM. */
  public RunHandle run(ScratchProject project, RunConfig config, RunListener listener)
      throws IOException {
    Path buildDir = project.root().resolve(".scratch4j/build");
    Path outDir = buildDir.resolve("classes");
    Path launcherDir = buildDir.resolve("launcher");
    Files.createDirectories(launcherDir);
    Path launcher = launcherDir.resolve("Scratch4JLauncher.java");
    Files.writeString(launcher, LauncherSource.generate(project.settings()),
        java.nio.charset.StandardCharsets.UTF_8);

    List<Path> sources = new ArrayList<>(project.javaSources());
    sources.add(launcher);
    CompileResult result = new CompilerService()
        .compile(sources, classpath(project), outDir);
    if (!result.success()) {
      listener.onCompileFailed(result);
      throw new CompilationFailedException(result);
    }
    listener.onCompileSucceeded(result);

    List<String> command = new ArrayList<>();
    command.add(config.javaExecutable().toString());
    if (config.exitAfterSeconds() > 0) {
      command.add("-Dscratch4j.ide.exitAfter=" + config.exitAfterSeconds());
    }
    if (config.control()) {
      command.add("-Dscratch4j.ide.control=true");
    }
    if (config.debugAgent() != null) {
      command.add(config.debugAgent());
      // hot reload of new attributes and methods, when this Java can do it
      command.addAll(ProgramRuntime.hotReloadArgs(config.javaExecutable()));
    }
    if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
      // With display scaling AWT measures the screen in scaled pixels and JOGL
      // in real ones, so fullScreen() fills only part of it and windows are
      // centred off it. Processing only fixes this with the Processing IDE's
      // fenster.exe at hand; set it here so any library version runs right.
      command.add("-Dsun.java2d.uiScale=1");
    }
    command.addAll(config.extraJvmArgs());
    // the stage designer shows each stage as this run first draws it
    command.add("-Dscratch4j.capture="
        + StagePreview.folder(project, "stage").getParent().toAbsolutePath());
    String omit = captureOmit(project);
    if (!omit.isEmpty()) {
      command.add("-Dscratch4j.capture.omit=" + omit);
    }
    command.add("-cp");
    command.add(classpathString(project, outDir));
    command.add("Scratch4JLauncher");
    command.add(config.stageClass());

    ProcessBuilder builder = GraphicsCompatibility.apply(new ProcessBuilder(command));
    builder.directory(project.root().toFile());
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      listener.onError("Could not start " + config.javaExecutable() + ": " + e.getMessage());
      throw e;
    }
    CompletableFuture<Integer> exit = new CompletableFuture<>();
    RunHandle handle = new RunHandle(process, exit);
    exit.whenComplete((code, t) -> listener.onExit(code == null ? -1 : code));
    RunHandle.pump(process, listener, exit);
    return handle;
  }

  /**
   * The sprites each stage's designer draws itself, which the captured
   * picture leaves out: {@code Stage:a,b;Other:c}.
   */
  static String captureOmit(ScratchProject project) {
    List<String> parts = new ArrayList<>();
    try {
      for (String stage : project.stageClasses()) {
        Path file = project.root().resolve(stage + ".java");
        if (!Files.isRegularFile(file)) continue;
        try {
          var document = org.openpatch.scratch4j.core.region.StageDocument.read(
              Files.readString(file));
          List<String> own = document.model().sprites().stream()
              .map(org.openpatch.scratch4j.core.region.SpriteRef::name)
              .filter(document::isSpriteEditable).toList();
          if (!own.isEmpty()) parts.add(stage + ":" + String.join(",", own));
        } catch (RuntimeException unreadable) {
          // unfinished Java in a block: the picture keeps all sprites
        }
      }
    } catch (IOException e) {
      return "";
    }
    return String.join(";", parts);
  }

  private List<Path> classpath(ScratchProject project) throws IOException {
    List<Path> entries = new ArrayList<>(project.libs());
    entries.add(project.root()); // project resources on the classpath
    return entries;
  }

  private String classpathString(ScratchProject project, Path outDir) throws IOException {
    List<String> entries = new ArrayList<>();
    entries.add(outDir.toString());
    entries.add(project.root().toString());
    for (Path jar : project.libs()) {
      entries.add(jar.toString());
    }
    return String.join(System.getProperty("path.separator"), entries);
  }

  /** Thrown when the project does not compile; the diagnostics are in the listener callback. */
  public static final class CompilationFailedException extends RuntimeException {
    private final CompileResult result;

    public CompilationFailedException(CompileResult result) {
      super("Compilation failed with " + result.errors().size() + " error(s)");
      this.result = result;
    }

    public CompileResult result() {
      return result;
    }
  }
}
