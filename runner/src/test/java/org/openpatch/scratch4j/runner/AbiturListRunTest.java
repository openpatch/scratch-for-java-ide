package org.openpatch.scratch4j.runner;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the generated monitor against both collection APIs without opening a window. */
class AbiturListRunTest {

  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reportsEmptyAndPopulatedSpriteLists(boolean nrw) throws Exception {
    Files.createDirectories(root.resolve("+libs"));
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    Files.copy(jar, root.resolve("+libs/scratch.jar"));
    Path library = Files.createDirectories(root.resolve("org/openpatch/scratch"));
    Files.writeString(library.resolve("Window.java"), """
        package org.openpatch.scratch;
        public class Window {
          private static final Window INSTANCE = new Window();
          public static Window getInstance() { return INSTANCE; }
          public Stage getStage() { return Stage.INSTANCE; }
          public void setDebug(boolean value) {}
          public void exit() {}
        }
        """);
    // The NRW API uses a generic return type because the project's List lives
    // in the default package. Inferring Collection here compiles but fails at runtime.
    Files.writeString(library.resolve("Stage.java"), """
        package org.openpatch.scratch;
        public class Stage {
          public static final Stage INSTANCE = new Stage();
          %s
        }
        """.formatted(nrw ? """
          public static Object contents;
          @SuppressWarnings("unchecked")
          public <L> L getAll() { return (L) contents; }
          """ : """
          public static java.util.List<Sprite> contents;
          public java.util.List<Sprite> getAll() { return contents; }
          """));
    Files.writeString(library.resolve("Sprite.java"), """
        package org.openpatch.scratch;
        public class Sprite {
          public double getX() { return 0; }
          public double getY() { return 0; }
          public double getDirection() { return 90; }
          public double getSize() { return 100; }
          public String getCurrentCostumeName() { return "cat"; }
          public boolean isVisible() { return true; }
        }
        """);
    // A project-owned List with the public Abitur API; it deliberately does
    // not implement java.util.Collection or Iterable.
    Files.writeString(root.resolve("List.java"), """
        public class List<T> {
          private final java.util.ArrayList<T> items = new java.util.ArrayList<>();
          private int current = -1;
          public void append(T item) { items.add(item); }
          public void toFirst() { current = items.isEmpty() ? -1 : 0; }
          public boolean hasAccess() { return current >= 0 && current < items.size(); }
          public T getContent() { return hasAccess() ? items.get(current) : null; }
          public void next() { if (hasAccess()) current++; }
        }
        """);
    Files.writeString(root.resolve("Probe.java"), """
        public class Probe {
          static class Cat extends org.openpatch.scratch.Sprite {
            int score;
            Cat(int score) { this.score = score; }
          }
          public static void main(String[] args) {
            var sprites = new %s<org.openpatch.scratch.Sprite>();
            org.openpatch.scratch.Stage.contents = sprites;
            System.out.println(Scratch4JLauncher.Monitors.json());
            sprites.%s(new Cat(7));
            sprites.%s(new Cat(13));
            System.out.println(Scratch4JLauncher.Monitors.json());
            for (int i = 2; i < 105; i++) sprites.%s(new Cat(i));
            System.out.println(Scratch4JLauncher.Monitors.json());
          }
        }
        """.formatted(nrw ? "List" : "java.util.ArrayList", nrw ? "append" : "add",
            nrw ? "append" : "add", nrw ? "append" : "add"));

    List<String> output = new CopyOnWriteArrayList<>();
    List<String> errors = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(ScratchProject.open(root), RunConfig.of("Probe"),
        new RunListener() {
          @Override public void onStdout(String line) { output.add(line); }
          @Override public void onStderr(String line) { errors.add(line); }
        });
    try {
      assertThat(handle.exitFuture().get(20, TimeUnit.SECONDS)).as("stderr: %s", errors).isZero();
      assertThat(output).hasSize(3);
      ProgramState empty = ProgramState.parse(output.get(0));
      assertThat(empty.total()).isZero();
      assertThat(empty.sprites()).isEmpty();
      ProgramState populated = ProgramState.parse(output.get(1));
      assertThat(populated.total()).isEqualTo(2);
      assertThat(populated.sprites()).extracting(ProgramState.Entry::className)
          .containsExactly("Cat", "Cat");
      assertThat(populated.sprites()).extracting(s -> s.fields().get(0).value())
          .containsExactly("7", "13");
      assertThat(populated.sprites().get(0).props())
          .contains(new ProgramState.Value("@costume", "cat"));
      ProgramState capped = ProgramState.parse(output.get(2));
      assertThat(capped.total()).isEqualTo(105);
      assertThat(capped.sprites()).hasSize(100);
    } finally {
      handle.stop();
    }
  }
}
