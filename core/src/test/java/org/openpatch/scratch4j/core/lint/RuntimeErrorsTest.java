package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeErrorsTest {

  private static final Function<String, Path> PROJECT = file ->
      List.of("MyStage.java", "Player.java").contains(file) ? Path.of("/p", file) : null;

  private static List<RuntimeErrors.Crash> collect(String output) {
    var collector = new RuntimeErrors.Collector();
    List<RuntimeErrors.Crash> crashes = new ArrayList<>();
    for (String line : output.split("\n")) {
      collector.feed(line).ifPresent(crashes::add);
    }
    collector.flush().ifPresent(crashes::add);
    return crashes;
  }

  @Test
  void nullInRunIsExplainedAtTheStudentsLine() {
    // exactly what a crash in a running stage prints (RuntimeErrorRunIT)
    var crashes = collect("""
        MESA-EGL: warning: DRI3 error: Could not get DRI3 device
        java.lang.NullPointerException: Cannot invoke "Player.move(double)" because "this.enemy" is null
        \tat MyStage.run(MyStage.java:23)
        \tat org.openpatch.scratch.Stage.pre(Stage.java:1804)
        \tat processing.core.PApplet.handleDraw(PApplet.java:2102)
        \tat java.base/java.util.TimerThread.run(Timer.java:522)
        score 3""");
    assertThat(crashes).hasSize(1);
    var e = RuntimeErrors.explain(crashes.get(0), PROJECT, Language.EN);
    assertThat(e.title()).isEqualTo("enemy is null");
    assertThat(e.hint()).contains("enemy = new Enemy();");
    assertThat(e.file()).isEqualTo(Path.of("/p/MyStage.java"));
    assertThat(e.line()).isEqualTo(23);
    assertThat(e.method()).isEqualTo("MyStage.run");
    assertThat(RuntimeErrors.explain(crashes.get(0), PROJECT, Language.DE).title())
        .isEqualTo("enemy ist null");
  }

  @Test
  void commonCrashesGetTheirExplanations() {
    var crashes = collect("""
        Exception in thread "main" java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 5
        \tat Player.whenClicked(Player.java:12)
        java.util.ConcurrentModificationException
        \tat java.base/java.util.ArrayList$Itr.checkForComodification(ArrayList.java:1095)
        \tat MyStage.run(MyStage.java:40)
        java.lang.RuntimeException: wrapped
        \tat org.openpatch.scratch.Stage.pre(Stage.java:1)
        Caused by: java.lang.ArithmeticException: / by zero
        \tat Player.speed(Player.java:7)
        \t... 3 more
        java.lang.StackOverflowError
        \tat Player.getX(Player.java:30)
        \tat Player.getX(Player.java:30)
        \tat Player.getX(Player.java:30)
        \tat Player.getX(Player.java:30)
        """);
    assertThat(crashes).hasSize(4);
    var index = RuntimeErrors.explain(crashes.get(0), PROJECT, Language.EN);
    assertThat(index.title()).isEqualTo("There is no index 5");
    assertThat(index.explanation()).contains("numbered 0 to 4");
    assertThat(index.line()).isEqualTo(12);
    var modified = RuntimeErrors.explain(crashes.get(1), PROJECT, Language.EN);
    assertThat(modified.hint()).contains("removeIf");
    assertThat(modified.file()).isEqualTo(Path.of("/p/MyStage.java"));
    var division = RuntimeErrors.explain(crashes.get(2), PROJECT, Language.DE);
    assertThat(division.title()).isEqualTo("Division durch 0");
    assertThat(division.line()).isEqualTo(7);
    var recursion = RuntimeErrors.explain(crashes.get(3), PROJECT, Language.EN);
    assertThat(recursion.explanation()).startsWith("Player.getX() calls itself");
  }

  @Test
  void returnValueAndLibraryErrors() {
    var npe = collect("""
        java.lang.NullPointerException: Cannot invoke "Player.getX()" because the return value of "MyStage.findPlayer()" is null
        \tat MyStage.run(MyStage.java:5)""");
    assertThat(RuntimeErrors.explain(npe.get(0), PROJECT, Language.EN).title())
        .isEqualTo("The result of findPlayer() is null");
    var library = collect("""
        java.lang.IllegalArgumentException: The map has no tile layer "walls". Tile layers: Floor, Walls
        \tat org.openpatch.scratch.extensions.tiled.TiledMap.stampLayer(TiledMap.java:210)
        \tat MyStage.run(MyStage.java:9)""");
    var e = RuntimeErrors.explain(library.get(0), PROJECT, Language.EN);
    assertThat(e.explanation()).contains("Tile layers: Floor, Walls");
    assertThat(e.line()).isEqualTo(9);
  }
}
