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

/** A real program under the JDI debugger: breakpoint, variables, step, continue. */
class DebuggerTest {

  @TempDir
  Path root;

  @Test
  void pausesShowsVariablesStepsAndContinues() throws Exception {
    Files.writeString(root.resolve("Counter.java"), """
        public class Counter {
          int score = 7;

          void count() {
            int total = 0;
            for (int i = 1; i <= 3; i++) {
              total += i;
            }
            score += total;
            System.out.println("score " + score);
          }

          static class Helper {
            void finish() {
              System.out.println("done");
            }
          }

          public static void main(String[] args) {
            new Counter().count();
            for (int i = 0; i < 3; i++) {
              new Helper().finish();
            }
          }
        }
        """);
    Files.createDirectories(root.resolve("+libs"));
    Path jar = org.openpatch.scratch4j.core.project.NewProject.classpathJar(
        org.openpatch.scratch.internal.BuiltinAssets.class);
    Files.copy(jar, root.resolve("+libs").resolve(jar.getFileName()));
    ScratchProject project = ScratchProject.open(root);
    LinkedBlockingQueue<Debugger.Pause> pauses = new LinkedBlockingQueue<>();
    LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
    Debugger debugger = new Debugger(Map.of("Counter", Set.of(9)), pauses::add);
    Thread attach = new Thread(() -> {
      try {
        debugger.attach();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    attach.start();
    RunHandle handle = new ProjectRunner().run(project,
        RunConfig.of("Counter").withDebugger(debugger), new RunListener() {
          @Override public void onStdout(String line) { output.add(line); }
        });
    try {
      Debugger.Pause pause = pauses.poll(30, TimeUnit.SECONDS);
      assertThat(pause).isNotNull();
      assertThat(pause.sourceFile()).isEqualTo("Counter.java");
      assertThat(pause.line()).isEqualTo(9);
      assertThat(pause.method()).isEqualTo("count");
      assertThat(pause.locals()).contains(new Debugger.Variable("total", "int", "6"));
      assertThat(pause.fields()).contains(new Debugger.Variable("score", "int", "7"));

      // the object diagram's view of the paused program
      var objects = debugger.objects(Set.of("Counter"), 10);
      assertThat(objects).hasSize(1);
      assertThat(objects.get(0).className()).isEqualTo("Counter");
      assertThat(objects.get(0).values()).contains(new Debugger.Variable("score", "int", "7"));

      debugger.stepOver();
      Debugger.Pause next = pauses.poll(10, TimeUnit.SECONDS);
      assertThat(next.line()).isEqualTo(10);
      assertThat(next.fields()).contains(new Debugger.Variable("score", "int", "13"));

      // breakpoints change while the program runs: line 9 off, a nested class on
      debugger.setBreakpoints("Counter", Set.of(15));
      debugger.resume();
      assertThat(output.poll(10, TimeUnit.SECONDS)).isEqualTo("score 13");
      Debugger.Pause inHelper = pauses.poll(10, TimeUnit.SECONDS);
      assertThat(inHelper).as("new breakpoint in the nested class").isNotNull();
      assertThat(inHelper.line()).isEqualTo(15);
      assertThat(output.poll(300, TimeUnit.MILLISECONDS)).as("paused before printing").isNull();
      assertThat(inHelper.className()).isEqualTo("Counter$Helper");
      debugger.setBreakpoints("Counter", Set.of());
      debugger.resume();
      assertThat(output.poll(10, TimeUnit.SECONDS)).isEqualTo("done");
      assertThat(output.poll(10, TimeUnit.SECONDS)).as("removed: no more pauses")
          .isEqualTo("done");
      assertThat(output.poll(10, TimeUnit.SECONDS)).isEqualTo("done");
      assertThat(handle.exitFuture().get(10, TimeUnit.SECONDS)).isZero();
    } finally {
      handle.stop();
      debugger.close();
    }
  }
}
