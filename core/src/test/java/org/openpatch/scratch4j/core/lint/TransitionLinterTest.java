package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransitionLinterTest {

  private final TransitionLinter linter =
      new TransitionLinter(DiagnosticsExplanations.Language.EN);

  private List<TransitionLinter.Finding> lint(Map<String, String> files, String file) {
    Map<Path, String> sources = new LinkedHashMap<>();
    files.forEach((name, text) -> sources.put(Path.of(name), text));
    return linter.lint(Path.of(file), files.get(file), TransitionLinter.facts(sources));
  }

  private List<String> kinds(List<TransitionLinter.Finding> findings) {
    return findings.stream().map(f -> f.line() + ":" + f.kind()).toList();
  }

  @Test
  void foreverLoopInRunOrConstructorIsFlaggedButNotWithBreak() {
    String cat = """
        public class Cat extends Sprite {
          public Cat() {
            while (true) {
              this.move(1);
            }
          }
          public void run() {
            for (;;) { this.turnRight(1); }
          }
          public void whenClicked() {
            while (true) {
              if (this.isTouchingEdge()) break;
              this.move(1);
            }
          }
          void helper() {
            while (true) { }
          }
        }
        """;
    var findings = lint(Map.of("Cat.java", cat), "Cat.java");
    assertThat(kinds(findings)).containsExactly("3:forever", "8:forever");
    assertThat(findings.get(0).message()).contains("Cat()");
    assertThat(findings.get(1).explanation()).contains("run()");
  }

  @Test
  void indirectSpriteSubclassesAndLongLoopsInRun() {
    Map<String, String> files = Map.of(
        "Enemy.java", "public class Enemy extends AnimatedSprite { }",
        "Bat.java", """
            public class Bat extends Enemy {
              public void run() {
                for (int i = 0; i < 1000000; i++) { }
                for (int i = 0; i < 10; i++) { }
              }
            }
            """);
    assertThat(kinds(lint(files, "Bat.java"))).containsExactly("3:longloop");
  }

  @Test
  void sleepAndWaitInSpritesButNotInPlainClasses() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("Player.java", """
        public class Player extends Sprite {
          public void run() {
            try {
              Thread.sleep(500);
              wait();
            } catch (InterruptedException e) { }
          }
        }
        """);
    files.put("Util.java", """
        public class Util {
          static void pause() throws Exception { Thread.sleep(5); }
        }
        """);
    assertThat(kinds(lint(files, "Player.java"))).containsExactly("4:sleep", "5:sleep");
    assertThat(lint(files, "Util.java")).isEmpty();
  }

  @Test
  void windowInsideAStageAndASecondWindow() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("Game.java", """
        public class Game extends Window {
          public Game() { super(800, 600); }
          public static void main(String[] args) { new Game(); }
        }
        """);
    files.put("Level.java", """
        public class Level extends Stage {
          public void whenKeyPressed(KeyCode key) {
            new Window(400, 300);
          }
        }
        """);
    files.put("Big.java", """
        public class Big extends Stage {
          public static void main(String[] args) {
            Window window = new Window(1200, 800, "assets");
            window.setStage(new Big());
          }
        }
        """);
    assertThat(lint(files, "Game.java")).isEmpty();
    files.put("Twice.java", """
        public class Twice {
          public static void main(String[] args) {
            new Window(400, 300);
            new Window(800, 600);
          }
        }
        """);
    assertThat(kinds(lint(files, "Level.java"))).containsExactly("3:window.inside");
    // a window made in main before the first stage is fine; mains are separate programs
    assertThat(lint(files, "Big.java")).isEmpty();
    assertThat(kinds(lint(files, "Twice.java"))).containsExactly("4:window.second");
  }

  @Test
  void countersThatShouldBeStatic() {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("Coin.java", """
        public class Coin extends Sprite {
          int collected = 0;
          static int total = 0;
          double speed = 2;
          public void run() {
            if (this.isTouchingMousePointer()) {
              collected++;
              total++;
            }
            this.speed = 3;
          }
        }
        """);
    files.put("Hero.java", """
        public class Hero extends Sprite {
          int score;
          int steps;
          public void run() { score += 1; steps++; }
        }
        """);
    files.put("MyStage.java", """
        public class MyStage extends Stage {
          public MyStage() {
            this.add(new Coin());
            this.add(new Coin());
            this.add(new Hero());
          }
        }
        """);
    // Coin exists twice: its counter is per coin; Hero exists once: only "score" sounds shared
    assertThat(kinds(lint(files, "Coin.java"))).containsExactly("2:static");
    assertThat(kinds(lint(files, "Hero.java"))).containsExactly("2:static");
    assertThat(lint(files, "MyStage.java")).isEmpty();
  }

  @Test
  void germanMessages() {
    var german = new TransitionLinter(DiagnosticsExplanations.Language.DE);
    String cat = "public class Cat extends Sprite { public void run() { while (true) { } } }";
    var findings = german.lint(Path.of("Cat.java"), cat,
        TransitionLinter.facts(Map.of(Path.of("Cat.java"), cat)));
    assertThat(findings).singleElement()
        .satisfies(f -> assertThat(f.message()).contains("Schleife endet nie"));
  }
}
