package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A running program gets a changed method body without a restart. */
class HotSwapTest {

  @TempDir
  Path root;

  private static String poll(LinkedBlockingQueue<String> out, String wanted) throws Exception {
    long until = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < until) {
      String line = out.poll(500, TimeUnit.MILLISECONDS);
      if (line != null && line.equals(wanted)) return line;
    }
    return null;
  }

  @Test
  void methodBodiesSwapAndNewFieldsAskForARestart() throws Exception {
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
        RunConfig.of("Game").withDebugger(session), new RunListener() {
          @Override public void onStdout(String line) { out.add(line); }
        });
    try {
      assertThat(poll(out, "speed 5")).isNotNull();
      assertThat(HotSwap.apply(project, session).status())
          .isEqualTo(HotSwap.Status.NOTHING_CHANGED);

      Files.writeString(source, game.replace("return 5;", "return 8;"));
      HotSwap.Result swapped = HotSwap.apply(project, session);
      assertThat(swapped.status()).isEqualTo(HotSwap.Status.SWAPPED);
      assertThat(swapped.classes()).containsExactly("Game");
      assertThat(poll(out, "speed 8")).as("the running loop calls the new body").isNotNull();

      // a changed constructor swaps too, but the student hears it shows after a restart
      HotSwap live = new HotSwap(project, session);
      Files.writeString(source, game.replace("return 5;", "return 8;")
          .replace("public static void main", "public Game() {\n    System.out.println(\"new\");\n  }"
              + "\n\n  public static void main"));
      HotSwap.Result withSetup = live.apply();
      assertThat(withSetup.status()).as(withSetup.detail()).isEqualTo(HotSwap.Status.SWAPPED);
      assertThat(withSetup.setupChanged()).containsExactly("Game");
      Files.writeString(source, game.replace("return 5;", "return 9;")
          .replace("public static void main", "public Game() {\n    System.out.println(\"new\");\n  }"
              + "\n\n  public static void main"));
      assertThat(live.apply().setupChanged()).as("once per run").isEmpty();

      Files.writeString(source, game.replace("return 5;", "return 8 +;"));
      assertThat(HotSwap.apply(project, session).status())
          .isEqualTo(HotSwap.Status.COMPILE_ERRORS);

      Files.writeString(source, game.replace("int speed() {", "int bonus = 1;\n  int speed() {"));
      assertThat(HotSwap.apply(project, session).status())
          .isEqualTo(HotSwap.Status.RESTART_NEEDED);
    } finally {
      handle.stop();
      session.close();
    }
  }
}
