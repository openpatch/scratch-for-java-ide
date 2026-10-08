package org.openpatch.scratch4j.core.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations;
import org.openpatch.scratch4j.core.lint.ProjectCheck;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ScratchProject;

/**
 * Every bundled lesson, played like a student on its real template: each
 * step's edit is made where its text says (with the step's own code), the
 * real compiler checks the project, and exactly that step ticks off - not
 * before its edit, and not a later one by accident.
 */
class BundledLessonsTest {

  @TempDir
  Path tmp;

  /** What a student does for one step. {@code CODE} stands for the step's code. */
  sealed interface Action {}

  record Run() implements Action {}

  /** Replaces every {@code find} in {@code file}; {@code CODE} in {@code replace} is the step's code. */
  record Edit(String file, String find, String replace) implements Action {}

  /** A new file with the step's code. */
  record Create(String file) implements Action {}

  private static final String CODE = "\u0000CODE\u0000";

  private static Action run() {
    return new Run();
  }

  private static Action edit(String file, String find, String replace) {
    return new Edit(file, find, replace);
  }

  /** Puts the step's code on the lines after {@code anchor}. */
  private static Action after(String file, String anchor) {
    return new Edit(file, anchor, anchor + CODE + "\n");
  }

  static Map<String, List<List<Action>>> walks() {
    Map<String, List<List<Action>>> walks = new LinkedHashMap<>();
    walks.put("make-it-walk", List.of(
        List.of(run()),
        List.of(edit("Walker.java", "this.move(3);", "this.move(6);")),
        List.of(edit("Walker.java", "this.setAnimationInterval(150);",
            "this.setAnimationInterval(80);")),
        List.of(run()),
        List.of(after("Walker.java", "      this.switchCostume(\"alienGreen_stand\");\n    }\n")),
        List.of(edit("Walker.java", "    this.setY(-50);\n",
                "    this.setY(-50);\n    this.addCostume(\"alienGreen_jump\");\n"),
            edit("Walker.java", "      this.changeY(4);\n",
                "      this.changeY(4);\n      this.switchCostume(\"alienGreen_jump\");\n")),
        List.of(run())));
    walks.put("catch-the-coins", List.of(
        List.of(run()),
        List.of(edit("Coin.java", "this.changeY(-3);", "this.changeY(-5);")),
        List.of(edit("CatchStage.java", "i < 4;", "i < 8;")),
        List.of(after("CatchStage.java", "    this.playSound(\"handleCoins\");\n    this.showScore();\n")),
        List.of(new Create("Diamond.java")),
        List.of(after("CatchStage.java", "      this.add(new Coin());\n    }\n")),
        List.of(run())));
    walks.put("red-light-green-light", List.of(
        List.of(run()),
        List.of(edit("Referee.java", "everyMillis(2200)", "everyMillis(1500)")),
        List.of(edit("RaceStage.java", "\"snail\", -60, 1.0", "\"snail\", -60, 2.0")),
        List.of(run()),
        List.of(after("RaceStage.java", "    this.add(new Racer(\"snail\", -60, 2.0));\n")),
        List.of(edit("Referee.java", "this.say(\"Go!\");", "this.say(\"Run!\");")),
        List.of(run())));
    walks.put("guess-the-number", List.of(
        List.of(run()),
        List.of(edit("GuessStage.java", "Random.randomInt(1, 10)", "Random.randomInt(1, 100)"),
            edit("GuessStage.java", "between 1 and 10\"", "between 1 and 100\"")),
        List.of(run()),
        List.of(after("GuessStage.java", "    this.tries = this.tries + 1;\n")),
        List.of(after("GuessStage.java", "+ this.tries + \" tries.\");\n")),
        List.of(run())));
    walks.put("bouncy-hedgehog", List.of(
        List.of(run()),
        List.of(edit("HedgehogSprite.java", "this.move(1);", "this.move(3);")),
        List.of(edit("TrampolineSprite.java", "this.changeX(-10);", "this.changeX(-20);"),
            edit("TrampolineSprite.java", "this.changeX(10);", "this.changeX(20);")),
        List.of(run()),
        List.of(after("HedgehogSprite.java",
            "        this.pointInDirection(Random.random(-45, 45));\n")),
        // the method below run(), and the import the light bulb adds
        List.of(edit("HedgehogSprite.java", "      this.say(\"Ouch!\", 2000);\n    }\n  }\n",
                "      this.say(\"Ouch!\", 2000);\n    }\n  }\n\n" + CODE + "\n"),
            edit("HedgehogSprite.java", "import org.openpatch.scratch.Random;\n",
                "import org.openpatch.scratch.Random;\nimport org.openpatch.scratch.KeyCode;\n")),
        List.of(run())));
    walks.put("dodge-the-rocks", List.of(
        List.of(run()),
        List.of(edit("Rock.java", "this.changeY(-3);", "this.changeY(-5);")),
        List.of(edit("GameStage.java", "i < 3;", "i < 5;")),
        List.of(edit("TitleStage.java", "Press SPACE to play", "SPACE starts the game")),
        List.of(run()),
        List.of(new Create("Bomb.java")),
        List.of(after("GameStage.java", "      this.add(new Rock());\n    }\n")),
        List.of(run())));
    return walks;
  }

  static List<String> lessons() {
    return List.copyOf(walks().keySet());
  }

  @ParameterizedTest
  @MethodSource("lessons")
  void aStudentCanWalkThroughTheWholeLesson(String id) throws IOException {
    Lesson lesson = Lesson.bundled(id);
    List<List<Action>> walk = walks().get(id);
    assertThat(lesson.steps()).as("one walk per step").hasSameSizeAs(walk);
    for (Lesson.Step step : lesson.steps()) {
      for (Locale locale : List.of(Locale.GERMAN, Locale.ENGLISH)) {
        assertThat(step.title(locale)).as(step.id()).isNotBlank();
        assertThat(step.text(locale)).as(step.id()).isNotBlank();
      }
      assertThat(step.text(Locale.GERMAN)).isNotEqualTo(step.text(Locale.ENGLISH));
    }
    Path root = BundledTemplates.create(lesson.template(), tmp, id,
        NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class));
    assertThat(compiles(root)).as("the template compiles").isTrue();
    Lesson.install(root, id);
    LessonProgress progress = LessonProgress.load(Lesson.load(root), root);

    for (int i = 0; i < walk.size(); i++) {
      Lesson.Step step = lesson.steps().get(i);
      assertThat(progress.update(files(root), compiles(root))).as("%s before its edit", step.id())
          .isEmpty();
      boolean ran = false;
      for (Action action : walk.get(i)) {
        switch (action) {
          case Run r -> ran = true;
          case Edit e -> {
            Path file = root.resolve(e.file());
            String text = Files.readString(file);
            assertThat(text).as("%s: %s", step.id(), e.find()).contains(e.find());
            Files.writeString(file, text.replace(e.find(),
                e.replace().replace(CODE, step.code() == null ? "" : step.code())));
          }
          case Create c -> Files.writeString(root.resolve(c.file()), step.code() + "\n");
        }
      }
      boolean compiles = compiles(root);
      assertThat(compiles).as("%s: the project compiles after the edit", step.id()).isTrue();
      List<String> done = (ran ? progress.ran(files(root), compiles)
          : progress.update(files(root), compiles)).stream().map(Lesson.Step::id).toList();
      assertThat(done).as("%s ticks off, and only it", step.id()).containsExactly(step.id());
    }
    assertThat(progress.finished()).isTrue();
  }

  private static Map<String, String> files(Path root) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    for (Path source : ScratchProject.open(root).javaSources()) {
      files.put(source.getFileName().toString(), Files.readString(source));
    }
    return files;
  }

  private static boolean compiles(Path root) throws IOException {
    return ProjectCheck.check(ScratchProject.open(root), DiagnosticsExplanations.Language.EN)
        .stream().noneMatch(ProjectCheck.Problem::error);
  }
}
