package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IDE's control channel against a real OpenGL program (Xvfb,
 * {@code -Dscratch4j.gltest=true}): frame heartbeats, screenshot, GIF
 * recording, and a frozen run() that stops the frame count.
 */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class IdeControlIT {

  @TempDir
  Path tmp;

  private static Path allJar() throws Exception {
    return LibraryJarSource.allJar(Path.of(System.getProperty("user.dir"), "target", "bundled"));
  }

  private static void waitFor(java.util.function.BooleanSupplier condition, long millis)
      throws InterruptedException {
    long end = System.currentTimeMillis() + millis;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
      Thread.sleep(100);
    }
  }

  @Test
  void heartbeatScreenshotAndGif() throws Exception {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "ctl", allJar());
    ScratchProject project = ScratchProject.open(tmp.resolve("ctl"));
    AtomicLong frames = new AtomicLong(-2);
    List<String> saved = new CopyOnWriteArrayList<>();
    List<String> stdout = new CopyOnWriteArrayList<>();
    List<String> stderr = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(project,
        RunConfig.of(project.startStage()).withControl().withExitAfter(20),
        new RunListener() {
          @Override public void onFrames(long f) { frames.set(f); }
          @Override public void onSaved(String path) { saved.add(path); }
          @Override public void onStdout(String line) { stdout.add(line); }
          @Override public void onStderr(String line) { stderr.add(line); }
        });
    try {
      waitFor(() -> frames.get() > 30, 15000);
      assertThat(frames.get()).as("frames are drawn; stderr %s", stderr).isGreaterThan(30);
      Path shot = tmp.resolve("shot.png");
      handle.send("debug on");
      handle.send("screenshot " + shot);
      waitFor(() -> Files.exists(shot) && !saved.isEmpty(), 5000);
      assertThat(shot).isRegularFile();
      assertThat(javax.imageio.ImageIO.read(shot.toFile()).getWidth()).isEqualTo(480);

      Path gif = tmp.resolve("clip.gif");
      handle.send("gif start " + gif);
      Thread.sleep(1500);
      handle.send("gif stop");
      waitFor(() -> saved.size() >= 2, 5000);
      assertThat(gif).isRegularFile();
      assertThat(Files.size(gif)).isGreaterThan(1000);
      // control lines never reach the console
      assertThat(stderr).noneMatch(line -> line.contains("@@scratch4j-ide@@"));
    } finally {
      handle.stop();
    }
  }

  @Test
  void aForeverLoopInRunStopsTheFrameCount() throws Exception {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "frozen", allJar());
    Path stage = tmp.resolve("frozen/MyStage.java");
    Files.writeString(stage, Files.readString(stage).replace("  public void run() {\n  }",
        "  int ticks;\n  public void run() {\n    ticks++;\n    if (ticks > 60) {\n"
        + "      while (true) { }\n    }\n  }"));
    ScratchProject project = ScratchProject.open(tmp.resolve("frozen"));
    List<Long> beats = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(project,
        RunConfig.of(project.startStage()).withControl(),
        new RunListener() {
          @Override public void onFrames(long f) { beats.add(f); }
        });
    try {
      waitFor(() -> beats.size() >= 8 && beats.get(beats.size() - 1) > 0, 20000);
      long last = beats.get(beats.size() - 1);
      Thread.sleep(3000);
      assertThat(beats.get(beats.size() - 1)).as("frozen: no new frames").isEqualTo(last);
      // run() starts a few frames after the first drawn frame
      assertThat(last).isBetween(55L, 200L);
    } finally {
      handle.stop();
    }
  }
}
