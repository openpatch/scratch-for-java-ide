package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With a JetBrains Runtime (bundled, downloaded or in ~/.jdks), new attributes
 * and methods go into the running program too, new attributes with their
 * starting values. Skipped where no such runtime is installed.
 */
class EnhancedHotSwapTest {

  @TempDir
  Path root;

  @Test
  void newAttributesAndMethodsWhileRunning() throws Exception {
    Path java = ProgramRuntime.enhanced();
    Assumptions.assumeTrue(java != null, "no runtime with enhanced class redefinition");
    Path source = root.resolve("Game.java");
    String game = """
        public class Game {
          int speed() {
            return 5;
          }

          public static void main(String[] args) throws Exception {
            Game game = new Game();
            for (int i = 0; i < 300; i++) {
              System.out.println("speed " + game.speed());
              Thread.sleep(100);
            }
          }
        }
        """;
    Files.writeString(source, game);
    Files.createDirectories(root.resolve("+libs"));
    Path jar = org.openpatch.scratch4j.core.project.NewProject.classpathJar(
        org.openpatch.scratch.internal.BuiltinAssets.class);
    Files.copy(jar, root.resolve("+libs").resolve(jar.getFileName()));
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
        RunConfig.of("Game").withDebugger(session).withJava(java), new RunListener() {
          @Override public void onStdout(String line) { out.add(line); }
        });
    try {
      assertThat(poll(out, "speed 5")).isTrue();
      HotSwap live = new HotSwap(project, session);
      Files.writeString(source, game
          .replace("  int speed() {", "  int extra = 4;\n\n  int speed() {")
          .replace("return 5;", "return 5 + bonus();")
          .replace("  public static void main",
              "  int bonus() {\n    return extra;\n  }\n\n  public static void main"));
      HotSwap.Result result = live.apply();
      assertThat(result.status()).as(result.detail()).isEqualTo(HotSwap.Status.SWAPPED);
      assertThat(result.values()).extracting(HotSwap.ValueUpdate::field).containsExactly("extra");
      assertThat(poll(out, "speed 9")).as("new method and the new attribute's value").isTrue();
    } finally {
      handle.stop();
      session.close();
    }
  }

  private static boolean poll(LinkedBlockingQueue<String> out, String wanted) throws Exception {
    long until = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < until) {
      String line = out.poll(500, TimeUnit.MILLISECONDS);
      if (wanted.equals(line)) return true;
    }
    return false;
  }
}
