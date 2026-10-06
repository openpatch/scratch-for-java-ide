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

  /** A stage with one counting cat: per-sprite, shared and list variables. */
  private ScratchProject countingProject() throws Exception {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "count", allJar());
    Files.writeString(tmp.resolve("count/MyStage.java"), """
        import org.openpatch.scratch.Stage;

        public class MyStage extends Stage {
          public MyStage() {
            super(480, 360);
            this.add(new Cat());
          }
        }
        """);
    Files.writeString(tmp.resolve("count/Cat.java"), """
        import org.openpatch.scratch.Sprite;
        import java.util.ArrayList;
        import java.util.List;

        public class Cat extends Sprite {
          static int cats = 1;
          int score;
          String name = "Tom";
          List<Integer> steps = new ArrayList<>(List.of(1, 2, 3));

          public Cat() {
            this.addCostume("bunny1_stand");
          }

          public void run() {
            score++;
            this.changeX(1);
          }
        }
        """);
    return ScratchProject.open(tmp.resolve("count"));
  }

  @Test
  void pauseStepSpeedAndTheVariablesReport() throws Exception {
    ScratchProject project = countingProject();
    AtomicLong frames = new AtomicLong(-2);
    AtomicLong pausedAt = new AtomicLong(-1);
    List<ProgramState> states = new CopyOnWriteArrayList<>();
    List<String> stderr = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(project,
        RunConfig.of("MyStage").withControl().withExitAfter(40),
        new RunListener() {
          @Override public void onFrames(long f) { frames.set(f); }
          @Override public void onPaused(long frame) { pausedAt.set(frame); }
          @Override public void onState(ProgramState state) { states.add(state); }
          @Override public void onStderr(String line) { stderr.add(line); }
        });
    try {
      // run() starts once the assets are loaded
      waitFor(() -> !states.isEmpty() && score(states.getLast()) > 5, 15000);
      assertThat(states).as("variables are reported; stderr %s", stderr).isNotEmpty();
      ProgramState running = states.getLast();
      assertThat(running.stage().className()).isEqualTo("MyStage");
      ProgramState.Entry cat = running.sprites().get(0);
      assertThat(cat.className()).isEqualTo("Cat");
      assertThat(cat.props()).extracting(ProgramState.Value::name)
          .contains("@x", "@y", "@direction", "@costume");
      assertThat(cat.fields()).contains(new ProgramState.Value("name", "\"Tom\""),
          new ProgramState.Value("steps", "ArrayList (3) [1, 2, 3]"));
      assertThat(running.statics()).singleElement()
          .satisfies(s -> assertThat(s.fields()).containsExactly(new ProgramState.Value("cats", "1")));

      handle.send("pause");
      waitFor(() -> pausedAt.get() > 0, 5000);
      long paused = pausedAt.get();
      ProgramState atPause = states.getLast();
      assertThat(atPause.paused()).isTrue();
      Thread.sleep(1500);
      assertThat(frames.get()).as("no frames while paused").isEqualTo(paused);

      handle.send("step");
      waitFor(() -> pausedAt.get() == paused + 1, 5000);
      assertThat(pausedAt.get()).as("one step is one frame").isEqualTo(paused + 1);
      int scoreAtPause = score(atPause);
      waitFor(() -> score(states.getLast()) == scoreAtPause + 1, 3000);
      assertThat(score(states.getLast())).isEqualTo(scoreAtPause + 1);

      // a pinned monitor is drawn onto the stage, top left, in Scratch's orange
      handle.send("pin " + cat.id() + " score Cat: score");
      handle.send("speed 10");
      handle.send("resume");
      Path shot = tmp.resolve("pinned.png");
      Thread.sleep(500);
      handle.send("screenshot " + shot);
      waitFor(() -> Files.exists(shot), 5000);
      var image = javax.imageio.ImageIO.read(shot.toFile());
      boolean orange = false;
      for (int x = 0; x < 160 && !orange; x++) {
        for (int y = 0; y < 34 && !orange; y++) {
          java.awt.Color c = new java.awt.Color(image.getRGB(x, y));
          orange = c.getRed() > 230 && c.getGreen() > 120 && c.getGreen() < 160 && c.getBlue() < 60;
        }
      }
      assertThat(orange).as("the pinned monitor is on the stage").isTrue();

      // slowed down to 10 frames per second
      long before = frames.get();
      Thread.sleep(3000);
      long perSecond = (frames.get() - before) / 3;
      assertThat(perSecond).as("frames per second at speed 10").isBetween(5L, 15L);
      assertThat(stderr).noneMatch(line -> line.contains("@@scratch4j-ide@@"));
    } finally {
      handle.stop();
    }
  }

  private static int score(ProgramState state) {
    if (state.sprites().isEmpty()) return -1;
    return state.sprites().get(0).fields().stream().filter(v -> v.name().equals("score"))
        .map(v -> Integer.parseInt(v.value())).findFirst().orElse(-1);
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
