package org.openpatch.scratch4j.core.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations;
import org.openpatch.scratch4j.core.lint.ProjectCheck;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ScratchProject;

class LessonTest {

  @TempDir
  Path tmp;

  private Path root;

  private Map<String, String> files() throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    for (Path source : ScratchProject.open(root).javaSources()) {
      files.put(source.getFileName().toString(), Files.readString(source));
    }
    return files;
  }

  /** What the IDE knows after a check: the files and whether the real compiler accepts them. */
  private boolean compiles() throws IOException {
    return ProjectCheck.check(ScratchProject.open(root), DiagnosticsExplanations.Language.EN)
        .stream().noneMatch(ProjectCheck.Problem::error);
  }

  private List<String> afterCheck(LessonProgress progress) throws IOException {
    return progress.update(files(), compiles()).stream().map(Lesson.Step::id).toList();
  }

  private List<String> afterRun(LessonProgress progress) throws IOException {
    return progress.ran(files(), compiles()).stream().map(Lesson.Step::id).toList();
  }

  private void edit(String from, String to) throws IOException {
    Path bunny = root.resolve("Bunny.java");
    String text = Files.readString(bunny);
    assertThat(text).contains(from);
    Files.writeString(bunny, text.replace(from, to));
  }

  @Test
  void theFirstLessonWalksThroughItsTemplateStepByStep() throws IOException {
    Lesson lesson = Lesson.bundled("first-steps");
    assertThat(lesson.steps()).extracting(Lesson.Step::id)
        .containsExactly("run", "faster", "try", "up", "costume", "say", "play");
    for (Lesson.Step step : lesson.steps()) {
      assertThat(step.title(Locale.GERMAN)).isNotBlank().isNotEqualTo(step.title(Locale.ENGLISH));
      assertThat(step.text(Locale.GERMAN)).isNotBlank().isNotEqualTo(step.text(Locale.ENGLISH));
    }
    BundledTemplates.create(lesson.template(), tmp, "lesson",
        NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class));
    root = tmp.resolve("lesson");
    Lesson.install(root, lesson.id());
    assertThat(Lesson.load(root)).isEqualTo(lesson);
    // what the editor writes reads back the same
    assertThat(Lesson.parse(lesson.toJson())).isEqualTo(lesson);

    LessonProgress progress = LessonProgress.load(lesson, root);
    assertThat(afterCheck(progress)).as("nothing done before the first run").isEmpty();
    assertThat(afterRun(progress)).containsExactly("run");

    // a half-typed number does not count; the finished one does
    edit("this.move(4);\n    }\n    if", "this.move(8\n    }\n    if");
    assertThat(afterCheck(progress)).isEmpty();
    edit("this.move(8\n", "this.move(8);\n");
    assertThat(afterCheck(progress)).containsExactly("faster");
    assertThat(afterCheck(progress)).as("a run is needed, not a check").isEmpty();
    assertThat(afterRun(progress)).containsExactly("try");

    // the step's own code, pasted where the text says, compiles and completes it
    Lesson.Step up = lesson.steps().get(3);
    edit("    this.ifOnEdgeBounce();", up.code() + "\n    this.ifOnEdgeBounce();");
    assertThat(afterCheck(progress)).containsExactly("up");

    // the costume step's two lines go to two places; a commented line does not count
    edit("    this.setSize(50);", "    this.setSize(50);\n    // this.addCostume(\"bunny1_jump\");");
    edit("      this.changeY(5);", "      this.changeY(5);\n      this.switchCostume(\"bunny1_jump\");");
    assertThat(afterCheck(progress)).isEmpty();
    edit("    // this.addCostume(\"bunny1_jump\");", "    this.addCostume(\"bunny1_jump\");");
    assertThat(afterCheck(progress)).containsExactly("costume");

    // ahead of the text: progress survives a reload and the say step ticks off at once
    Lesson.Step say = lesson.steps().get(5);
    edit("    this.setRotationStyle(RotationStyle.LEFT_RIGHT);\n",
        "    this.setRotationStyle(RotationStyle.LEFT_RIGHT);\n" + say.code() + "\n");
    LessonProgress reloaded = LessonProgress.load(lesson, root);
    assertThat(reloaded.current()).isEqualTo(5);
    assertThat(afterCheck(reloaded)).containsExactly("say");
    assertThat(afterRun(reloaded)).containsExactly("play");
    assertThat(reloaded.finished()).isTrue();

    reloaded.restart();
    assertThat(LessonProgress.load(lesson, root).current()).isZero();
  }

  @Test
  void plainCodeChecksIgnoreSpacingButNotWords() throws IOException {
    var check = new Lesson.Contains("A.java", "this.say(\"Hi\", 2000);", true);
    var facts = (java.util.function.Function<String, Lesson.Facts>) text ->
        new Lesson.Facts(Map.of("A.java", text), true, false);
    assertThat(check.met(facts.apply("    this.say( \"Hi\" ,2000 ) ;"))).isTrue();
    assertThat(check.met(facts.apply("    this . say(\"Hi\", 2000);"))).isTrue();
    assertThat(check.met(facts.apply("    this.say(\"Hi\", 20000);"))).isFalse();
    assertThat(check.met(facts.apply("    // this.say(\"Hi\", 2000);"))).isFalse();
    var words = new Lesson.Contains("A.java", "int count", true);
    assertThat(words.met(facts.apply("int  count = 0;"))).isTrue();
    assertThat(words.met(facts.apply("intcount = 0;"))).isFalse();
    // regex characters in plain code are plain
    assertThat(new Lesson.Contains("A.java", "a[0] = b.c();", true)
        .met(facts.apply("a[0]=b.c();"))).isTrue();
    String json = new Lesson("x", "", Map.of("en", "X"), List.of(new Lesson.Step("s",
        Map.of("en", "S"), Map.of("en", "T"), null, check))).toJson();
    assertThat(json).contains("\"text\" : \"this.say(\\\"Hi\\\", 2000);\"");
    assertThat(Lesson.parse(json).steps().get(0).check()).isEqualTo(check);
  }

  private static Lesson.Facts facts(String... nameAndText) {
    Map<String, String> files = new LinkedHashMap<>();
    for (int i = 0; i < nameAndText.length; i += 2) {
      files.put(nameAndText[i], nameAndText[i + 1]);
    }
    return new Lesson.Facts(files, true, false);
  }

  @Test
  void aClassIsFoundByNameByWhatItExtendsAndByCount() {
    var project = facts(
        "Bunny.java", "public class Bunny extends Sprite { }",
        "Enemy.java", "public class Enemy extends AnimatedSprite { }",
        "Bee.java", "public class Bee extends Enemy { }",
        "Old.java", "// public class Coin extends Sprite { }");
    assertThat(new Lesson.ClassExists("Bee", "", 1).met(project)).isTrue();
    assertThat(new Lesson.ClassExists("Coin", "", 1).met(project)).as("commented out").isFalse();
    assertThat(new Lesson.ClassExists("Bee", "AnimatedSprite", 1).met(project))
        .as("through Enemy").isTrue();
    assertThat(new Lesson.ClassExists("Bee", "Sprite", 1).met(project)).isFalse();
    assertThat(new Lesson.ClassExists("", "AnimatedSprite", 2).met(project)).isTrue();
    assertThat(new Lesson.ClassExists("", "Sprite", 2).met(project))
        .as("a second sprite class is still missing").isFalse();
  }

  @Test
  void aMethodIsADeclarationNotACall() {
    var calls = facts("Bunny.java", "class Bunny { void run() { this.whenClicked(); } }");
    var declares = facts("Bunny.java",
        "class Bunny { public void whenClicked() {\n this.say(\"Hi\"); } }");
    assertThat(new Lesson.MethodExists("whenClicked", "").met(calls)).isFalse();
    assertThat(new Lesson.MethodExists("whenClicked", "").met(declares)).isTrue();
    assertThat(new Lesson.MethodExists("whenClicked", "Bunny.java").met(declares)).isTrue();
    assertThat(new Lesson.MethodExists("whenClicked", "Other.java").met(declares)).isFalse();
    assertThat(new Lesson.MethodExists("whenKeyPressed", "")
        .met(facts("A.java", "class A { public void whenKeyPressed(int keyCode) { } }"))).isTrue();
  }

  @Test
  void aLineIsChangedWhenEveryCopyIsGone() {
    var check = new Lesson.LineChanged("Bunny.java", "this.move(4);");
    assertThat(check.met(facts("Bunny.java", "this.move( 4 );\nthis.move(4);"))).isFalse();
    assertThat(check.met(facts("Bunny.java", "this.move(8);\nthis.move(4) ;"))).isFalse();
    assertThat(check.met(facts("Bunny.java", "this.move(8);\nthis.move(10);"))).isTrue();
    assertThat(check.met(facts("Bunny.java", "// this.move(4);\nthis.move(8);")))
        .as("a commented-out copy does not count").isTrue();
    assertThat(check.met(facts("Other.java", "x"))).as("no such file").isFalse();
  }

  @Test
  void theNewChecksAndTheLanguagesAreWrittenAndReadBack() throws IOException {
    var steps = List.of(
        new Lesson.Step("a", Map.of("de", "A"), Map.of("de", "Text"), null,
            new Lesson.ClassExists("", "Sprite", 2)),
        new Lesson.Step("b", Map.of("de", "B"), Map.of("de", "Text"), null,
            new Lesson.MethodExists("whenClicked", "Bunny.java")),
        new Lesson.Step("c", Map.of("de", "C"), Map.of("de", "Text"), null,
            new Lesson.LineChanged("Bunny.java", "this.move(4);")));
    Lesson german = new Lesson("x", "", List.of("de"), Map.of("de", "Nur Deutsch"), steps);
    assertThat(Lesson.parse(german.toJson())).isEqualTo(german);
    // an English IDE shows the German text instead of nothing
    assertThat(german.title(Locale.ENGLISH)).isEqualTo("Nur Deutsch");
    assertThat(german.steps().get(0).text(Locale.ENGLISH)).isEqualTo("Text");
    // without a list, the languages come from the title
    assertThat(Lesson.bundled("first-steps").languages()).containsExactly("de", "en");
  }

  @Test
  void aBrokenPatternIsReportedWhenTheLessonLoads() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> Lesson.parse(
        "{\"id\":\"x\",\"steps\":[{\"id\":\"s\",\"check\":{\"contains\":"
            + "{\"file\":\"A.java\",\"pattern\":\"say(\"}}}]}"))
        .isInstanceOf(IOException.class).hasMessageContaining("say(");
  }

  @Test
  void aProjectWithoutALessonHasNone() throws IOException {
    assertThat(Lesson.load(tmp)).isNull();
  }
}
