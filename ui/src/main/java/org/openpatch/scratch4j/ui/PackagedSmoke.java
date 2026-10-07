package org.openpatch.scratch4j.ui;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openpatch.scratch4j.core.project.*;
import org.openpatch.scratch4j.core.io.AtomicFiles;
import org.openpatch.scratch4j.runner.*;

/** Starts from the installed native launcher; every project and report is disposable. */
final class PackagedSmoke {
  private PackagedSmoke() {}
  static void start(StudioApp app, javafx.stage.Stage stage, Path output) {
    if (!stage.isShowing()) throw new IllegalStateException("Studio window did not open");
    Thread worker = new Thread(() -> {
      try {
        Files.createDirectories(output);
        Path bundled = StudioApp.bundledLibraryDir();
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        for (LibraryFlavour flavour : LibraryFlavour.values()) {
          String suffix = flavour == LibraryFlavour.NRW ? "-nrw" : "";
          Path jar = bundled.resolve("scratch-" + LibraryJarSource.SCRATCH_VERSION + suffix + "-all.jar");
          if (!Files.isRegularFile(jar)) throw new IllegalStateException("Missing offline library " + jar);
          String name = "first-" + flavour.id();
          NewProject.create(ProjectTemplate.CLASSES_FIRST, output, name, jar);
          ScratchProject project = ScratchProject.open(output.resolve(name));
          project.settings().flavour = flavour.id();
          project.settings().save(project.root());
          if (flavour == LibraryFlavour.NRW) {
            // The school's own List is not bundled for licensing reasons; no List operation is used here.
            Files.writeString(project.root().resolve("List.java"), "public class List<T> {}\n");
          }
          List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
          AtomicLong frames = new AtomicLong(-1);
          RunHandle handle = new ProjectRunner().run(project,
              RunConfig.of(project.startStage()).withJava(java).withControl().withExitAfter(4),
              new RunListener() {
                public void onStderr(String line) { errors.add(line); }
                public void onFrames(long count) { frames.set(count); }
              });
          int code;
          try { code = handle.exitFuture().get(40, TimeUnit.SECONDS); }
          catch (Exception e) { handle.stop(); throw e; }
          if (code != 0 || frames.get() <= 0) throw new IllegalStateException(name + " did not draw: " + code + " " + errors);
          Path source = project.root().resolve("MyStage.java");
          String saved = Files.readString(source);
          AtomicFiles.writeString(source, saved + "\n// saved in packaged Studio\n");
          // Reproduce a writer dying before its atomic rename; old saved code must survive.
          Path partial = Files.createTempFile(source.getParent(), source.getFileName().toString(), ".tmp");
          Files.writeString(partial, "interrupted");
          ScratchProject reopened = ScratchProject.open(project.root());
          if (!Files.readString(source).equals(saved + "\n// saved in packaged Studio\n")
              || reopened.javaSources().contains(partial)) throw new IllegalStateException("Save recovery failed");
          Files.delete(partial);
          Files.writeString(project.root().resolve("ScoreTest.java"),
              "import org.openpatch.scratch.*;\n@Test\nclass ScoreTest { @Test void movesWithRealCostume() { "
              + "Sprite sprite = new Sprite(\"slime\", \"slimeGreen\"); sprite.changeX(3); "
              + "assertEquals(3.0, sprite.getX()); assertTrue(sprite.getWidth() > 0); "
              + "Sprite cat = new Sprite(\"cat\", \"cat_idle_1\"); assertTrue(cat.getWidth() > 0); } }\n");
          RunHandle checks = new TeachingTestRunner().run(project, new RunListener() {
            public void onStderr(String line) { errors.add(line); }
          });
          try {
            if (checks.exitFuture().get(30, TimeUnit.SECONDS) != 0)
              throw new IllegalStateException("Packaged offline teaching tests failed: " + errors);
          } finally { checks.stop(); }
        }
        Files.writeString(output.resolve("report.txt"),
            "PASS: native Studio window; bundled Java/compiler/JUnit; offline standard/NRW project creation and teaching checks; rendered first projects; save/reopen and interrupted temporary write recovery\n");
        javafx.application.Platform.runLater(() -> { stage.close(); javafx.application.Platform.exit(); });
      } catch (Throwable error) {
        error.printStackTrace();
        try { Files.writeString(output.resolve("report.txt"), "FAIL: " + error); } catch (Exception ignored) {}
        System.exit(1);
      }
    }, "packaged-studio-smoke");
    worker.setDaemon(false);
    worker.start();
  }
  private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }
}
