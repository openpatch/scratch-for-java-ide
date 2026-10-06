package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectCheckTest {

  @TempDir
  Path tmp;

  @Test
  void cleanTemplateHasNoProblems() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "clean", libraryJar());
    assertThat(ProjectCheck.check(ScratchProject.open(tmp.resolve("clean")),
        DiagnosticsExplanations.Language.EN)).isEmpty();
  }

  @Test
  void aForeignLineInTheDesignerRegionIsReportedWithItsFix() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "region", libraryJar());
    Path stage = tmp.resolve("region/MyStage.java");
    String source = Files.readString(stage);
    int at = source.indexOf("// scratch4j:end setup");
    String foreign = source.substring(0, at) + "this.setTint(60);\n    "
        + source.substring(at);
    Files.writeString(stage, foreign);
    var problems = ProjectCheck.check(ScratchProject.open(tmp.resolve("region")),
        DiagnosticsExplanations.Language.EN);
    var region = problems.stream()
        .filter(p -> ProjectCheck.FIX_MOVE_OUT_OF_REGION.equals(p.fix())).toList();
    assertThat(region).hasSize(1);
    int line = foreign.lines().toList().indexOf("    this.setTint(60);") + 1;
    assertThat(region.get(0).line()).isEqualTo(line);
    assertThat(region.get(0).error()).isFalse();
  }

  @Test
  void compilerErrorsComeWithExplanations() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "broken", libraryJar());
    Path stage = tmp.resolve("broken/MyStage.java");
    Files.writeString(stage, """
        import org.openpatch.scratch.Stage;

        public class MyStage extends Stage {

          public MyStage() {
            super(480, 360);
            int x =
          }
        }
        """);
    var problems = ProjectCheck.check(ScratchProject.open(tmp.resolve("broken")),
        DiagnosticsExplanations.Language.DE);
    assertThat(problems).isNotEmpty();
    assertThat(problems.get(0).explanation()).isNotBlank(); // German explanation
    assertThat(problems.get(0).file()).isRegularFile();
    assertThat(problems.get(0).line()).isPositive();
  }

  @Test
  void errorsAfterASyntaxErrorAreMarkedAsFollowUps() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "echo", libraryJar());
    Files.writeString(tmp.resolve("echo/Player.java"), """
        public class Player extends Sprite {
          void go() {
            int x = 1
            if (x > 0 {
            }
          }
        }
        """);
    var problems = ProjectCheck.check(ScratchProject.open(tmp.resolve("echo")),
        DiagnosticsExplanations.Language.EN).stream()
        .filter(p -> p.file() != null && p.file().endsWith("Player.java")).toList();
    assertThat(problems).hasSizeGreaterThan(1);
    assertThat(problems.get(0).message()).isEqualTo("A semicolon ; is missing here");
    assertThat(problems.get(0).original()).isEqualTo("';' expected");
    assertThat(problems.get(0).followUp()).isFalse();
    assertThat(problems.subList(1, problems.size())).allMatch(ProjectCheck.Problem::followUp);
  }

  @Test
  void aLibraryClassWithoutImportGetsTheImportLine() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "noimport", libraryJar());
    Files.writeString(tmp.resolve("noimport/Player.java"), """
        public class Player extends Sprite {
        }
        """);
    var problem = ProjectCheck.check(ScratchProject.open(tmp.resolve("noimport")),
        DiagnosticsExplanations.Language.EN).stream()
        .filter(p -> p.file() != null && p.file().endsWith("Player.java")).findFirst()
        .orElseThrow();
    assertThat(problem.message()).isEqualTo("Sprite needs an import");
    assertThat(problem.explanation()).contains("import org.openpatch.scratch.Sprite;");
  }

  @Test
  void callbacksAreReadFromTheLibraryJar() throws IOException {
    var callbacks = LibraryCallbacks.of(java.util.List.of(libraryJar()));
    assertThat(callbacks.sprite()).containsKeys("run", "whenClicked", "whenKeyPressed");
    assertThat(callbacks.stage()).containsKey("run").doesNotContainKey("whenClicked");
    assertThat(callbacks.sprite().get("whenKeyPressed"))
        .isEqualTo(LibraryCallbacks.bundled().sprite().get("whenKeyPressed"));
  }

  @Test
  void assetLintsArePartOfTheCheck() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "linted", libraryJar());
    Files.writeString(tmp.resolve("linted/Player.java"), """
        import org.openpatch.scratch.Sprite;

        public class Player extends Sprite {

          public Player() {
            this.addCostume("buny1_stand");
          }

          public void run() {
          }
        }
        """);
    var problems = ProjectCheck.check(ScratchProject.open(tmp.resolve("linted")),
        DiagnosticsExplanations.Language.EN);
    assertThat(problems).hasSize(1);
    assertThat(problems.get(0).message()).contains("buny1_stand");
    assertThat(problems.get(0).suggestions()).isNotEmpty();
  }

  @Test
  void unknownNamesGetDidYouMeanFromTheLibraryAndTheProject() throws IOException {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "typos", libraryJar());
    Files.writeString(tmp.resolve("typos/Player.java"), """
        import org.openpatch.scratch.Sprite;

        public class Player extends Sprite {

          int score = 0;

          public Player() {
            this.addCostume("bunny1_stand");
          }

          public void run() {
            this.mvoe(3);
            scroe = scroe + 1;
          }
        }
        """);
    var problems = ProjectCheck.check(ScratchProject.open(tmp.resolve("typos")),
        DiagnosticsExplanations.Language.EN);
    assertThat(problems).extracting(ProjectCheck.Problem::error).containsOnly(true);
    assertThat(problems).anySatisfy(p -> {
      assertThat(p.message()).isEqualTo("Java does not know a method named mvoe()");
      assertThat(p.original()).startsWith("cannot find symbol");
      assertThat(p.suggestions()).contains("move");
      assertThat(p.column()).isPositive();
    });
    assertThat(problems).anySatisfy(p -> assertThat(p.suggestions()).contains("score"));
  }

  @Test
  void identifierAtSkipsTheDotOfAMemberSelect() throws IOException {
    Path file = tmp.resolve("A.java");
    Files.writeString(file, "class A {\n  void f() { this.mvoe(3); }\n}\n");
    // javac reports the column of the dot in "this.mvoe"
    int dot = "  void f() { this".length() + 1;
    assertThat(ProjectCheck.identifierAt(file, 2, dot)).isEqualTo("mvoe");
    assertThat(ProjectCheck.identifierAt(file, 9, 1)).isEmpty();
  }

  private Path libraryJar() throws IOException {
    return NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
  }
}
