package org.openpatch.scratch4j.runner;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openpatch.scratch4j.core.compile.*;
import org.openpatch.scratch4j.core.project.ScratchProject;

/** The same student checks run through JUnit in an isolated, bounded desktop JVM. */
public final class TeachingTestRunner {
  public RunHandle run(ScratchProject project, RunListener listener) throws IOException {
    List<Path> sources = project.javaSources();
    List<String> classes = new ArrayList<>();
    boolean graphics = false;
    Path manifest = project.root().resolve(".scratch4j/checks.json");
    if (Files.isRegularFile(manifest)) {
      var metadata = tools.jackson.databind.json.JsonMapper.builder().build().readTree(manifest.toFile());
      if (metadata.path("schemaVersion").asInt() != 1) throw new IOException("Unsupported behavioral check schema");
      String mode = metadata.path("mode").asText("logic");
      if (!mode.equals("logic") && !mode.equals("graphics")) throw new IOException("Unknown behavior check mode: " + mode);
      graphics = mode.equals("graphics");
      metadata.path("classes").forEach(value -> classes.add(value.asText()));
    } else {
      for (Path source : sources) {
        String text = Files.readString(source);
        if (!TeachingTests.hasTests(text)) continue;
        var pkg = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
        classes.add((pkg.find() ? pkg.group(1) + "." : "") + source.getFileName().toString().replaceFirst("\\.java$", ""));
      }
    }
    if (classes.isEmpty() || classes.stream().anyMatch(name -> !name.matches("[\\w$]+(?:\\.[\\w$]+)*"))) {
      throw new IOException("No valid @Test classes. Add checks or a .scratch4j/checks.json manifest.");
    }
    Path out = project.root().resolve(".scratch4j/build/checks");
    List<Path> classpath = new ArrayList<>(project.libs());
    classpath.add(project.root());
    CompileResult compiled = new CompilerService().compile(sources, classpath, out);
    if (!compiled.success()) { listener.onCompileFailed(compiled); throw new ProjectRunner.CompilationFailedException(compiled); }
    classpath.add(0, out);
    List<String> command = new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString()));
    if (!graphics) command.add("-Djava.awt.headless=true");
    command.addAll(List.of("-jar", TeachingTests.junitJar().toString(), "execute", "--disable-banner", "--fail-if-no-tests",
        "--class-path", String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList())));
    classes.forEach(name -> command.addAll(List.of("--select-class", name)));
    Process process = new ProcessBuilder(command).directory(project.root().toFile()).start();
    CompletableFuture<Integer> exit = new CompletableFuture<>();
    RunHandle handle = new RunHandle(process, exit);
    RunHandle.pump(process, listener, exit);
    exit.whenComplete((code, error) -> listener.onExit(code == null ? -1 : code));
    return handle;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: TeachingTestRunner <project-folder>");
    RunHandle handle = new TeachingTestRunner().run(ScratchProject.open(Path.of(args[0]).toAbsolutePath()), new RunListener() {
      public void onStdout(String line) { System.out.println(line); }
      public void onStderr(String line) { System.err.println(line); }
    });
    try { System.exit(handle.exitFuture().get(30, TimeUnit.SECONDS)); }
    catch (java.util.concurrent.TimeoutException e) { handle.stop(); throw new IOException("Behavior checks exceeded 30 seconds", e); }
  }
}
