package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A running stage's run() gets new code from a save, frame by frame, without a restart. */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class HotSwapRunIT {

  @TempDir
  Path tmp;

  @Test
  void stageRunChangesWhileTheWindowIsOpen() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "live", allJar);
    Path root = tmp.resolve("live");
    Path stage = root.resolve("MyStage.java");
    String source = Files.readString(stage).replace("public void run() {\n  }", """
        public void run() {
            System.out.println("TICK " + speed());
          }

          int speed() {
            return 1;
          }""");
    Files.writeString(stage, source);
    ScratchProject project = ScratchProject.open(root);
    Debugger session = new Debugger(Map.of(), pause -> { });
    Thread attach = new Thread(() -> {
      try {
        session.attach();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    attach.start();
    LinkedBlockingQueue<String> out = new LinkedBlockingQueue<>();
    RunHandle handle = new ProjectRunner().run(project,
        RunConfig.of("MyStage").withExitAfter(20).withDebugger(session)
            .withJava(ProgramRuntime.java()), new RunListener() {
          @Override public void onStdout(String line) { out.add(line); }
        });
    try {
      assertThat(waitFor(out, "TICK 1")).isTrue();
      Files.writeString(stage, source.replace("return 1;", "return 2;"));
      assertThat(HotSwap.apply(project, session).status()).isEqualTo(HotSwap.Status.SWAPPED);
      assertThat(waitFor(out, "TICK 2")).as("the next frames run the new code").isTrue();

      if (ProgramRuntime.enhanced() != null) {
        // on a JetBrains Runtime: a new attribute and method in a running stage
        HotSwap live = new HotSwap(project, session);
        Files.writeString(stage, source.replace("return 1;", "return 2 + extra();")
            .replace("int speed() {", "int bonus = 5;\n\n  int extra() {\n    return bonus;\n"
                + "  }\n\n  int speed() {"));
        assertThat(live.apply().status()).isEqualTo(HotSwap.Status.SWAPPED);
        assertThat(waitFor(out, "TICK 7")).as("new method and attribute in the stage").isTrue();
      }

      // the object diagram: the stage and the player it references
      var objects = session.objects(java.util.Set.of("MyStage", "Player"), 20);
      var stageObject = objects.stream().filter(o -> o.className().equals("MyStage"))
          .findFirst().orElseThrow();
      var playerObject = objects.stream().filter(o -> o.className().equals("Player"))
          .findFirst().orElseThrow();
      assertThat(stageObject.references()).contains(
          new Debugger.Reference("player", playerObject.id()));
    } finally {
      handle.stop();
      session.close();
    }
  }

  private static boolean waitFor(LinkedBlockingQueue<String> out, String line) throws Exception {
    long until = System.currentTimeMillis() + 20000;
    while (System.currentTimeMillis() < until) {
      String next = out.poll(500, TimeUnit.MILLISECONDS);
      if (line.equals(next)) return true;
    }
    return false;
  }
}
