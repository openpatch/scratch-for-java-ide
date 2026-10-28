package org.openpatch.scratch4j.export;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectSettings;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPageTest {

  @TempDir
  Path tmp;

  @Test
  void theLauncherStartsTheClassAsTheDesktopLauncherWouldWithoutReflection() {
    ProjectSettings settings = new ProjectSettings();
    settings.pixelArt = true;
    assertThat(WebPage.launcherSource(settings, "Game", WebPage.MainKind.STATIC_ARGS))
        .contains("Window.usePixelArt();").contains("Game.main(args);").doesNotContain("reflect");
    assertThat(WebPage.launcherSource(new ProjectSettings(), "MyStage", WebPage.MainKind.STAGE))
        .contains("new Window(480, 360).setStage(new MyStage());");
    assertThat(WebPage.launcherSource(new ProjectSettings(), "app.Game", WebPage.MainKind.INSTANCE))
        .contains("new app.Game().main();");
  }

  @Test
  void teaVmsProblemsBecomeOneSentenceNamingTheClass() {
    assertThat(WebPage.explain(List.of("Method java.lang.ProcessBuilder.start()Ljava/lang/Process; was not found",
        "    at Shell.main(Shell.java:3)")))
        .isEqualTo("The game uses java.lang.ProcessBuilder.start, which a web page does not have.");
  }

  /** Needs the library's web jar: -Dscratch4j.webJar=…/web/target/scratch-<v>-web.jar (or SCRATCH4J_WEB_JAR). */
  @Test
  void exportsATemplateAsAPage() throws Exception {
    String jar = System.getProperty("scratch4j.webJar", System.getenv("SCRATCH4J_WEB_JAR"));
    Assumptions.assumeTrue(jar != null && Files.isRegularFile(Path.of(jar)), "no web jar given");
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "game", null);
    Path root = tmp.resolve("game");
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");

    Path index = WebPage.export(ScratchProject.open(root), Path.of(jar), java, root.resolve("export/game-web"));

    assertThat(index).exists();
    assertThat(index.resolveSibling("game.wasm")).exists();
    assertThat(index.resolveSibling("files.txt")).exists();
    assertThat(Files.readString(index)).contains("<title>game</title>");
    assertThat(index.resolveSibling("MyStage.java")).doesNotExist();
  }

  @Test
  void anExtensionTheWebDoesNotHaveIsNamed() throws Exception {
    String jar = System.getProperty("scratch4j.webJar", System.getenv("SCRATCH4J_WEB_JAR"));
    Assumptions.assumeTrue(jar != null && Files.isRegularFile(Path.of(jar)), "no web jar given");
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "game", null);
    Path root = tmp.resolve("game");
    Files.writeString(root.resolve("Saver.java"), """
        import org.openpatch.scratch.gamepad.Gamepad;
        public class Saver { Gamepad pad; }
        """);
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");
    assertThatThrownBy(() -> WebPage.export(ScratchProject.open(root), Path.of(jar), java, root.resolve("export/web")))
        .isInstanceOf(WebPage.NotForTheWebException.class).hasMessageContaining("gamepad extension");
  }

  /** Needs the NRW web jar: -Dscratch4j.nrwWebJar=…/web/target/scratch-<v>-nrw-web.jar. */
  @Test
  void exportsAnNrwProjectWhoseStageGetsTheAbiturList() throws Exception {
    String jar = System.getProperty("scratch4j.nrwWebJar", System.getenv("SCRATCH4J_NRW_WEB_JAR"));
    Assumptions.assumeTrue(jar != null && Files.isRegularFile(Path.of(jar)), "no NRW web jar given");
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "abitur", null);
    Path root = tmp.resolve("abitur");
    ScratchProject project = ScratchProject.open(root);
    project.settings().flavour = "nrw";
    project.settings().save(root);
    // a stand-in for the Abitur's List: what the library needs of it
    Files.writeString(root.resolve("List.java"), """
        public class List<ContentType> {
          private final java.util.ArrayList<ContentType> items = new java.util.ArrayList<>();
          public void append(ContentType pContent) { items.add(pContent); }
          public boolean isEmpty() { return items.isEmpty(); }
        }
        """);
    Files.writeString(root.resolve("Counter.java"), """
        import org.openpatch.scratch.*;
        public class Counter extends Stage {
          public Counter() {
            this.add(new Sprite());
            List<Sprite> sprites = this.getAll();
            this.add(new Text(sprites.isEmpty() ? "empty" : "sprites", 0, 0, 200));
          }
          public static void main(String[] args) {
            new Window(400, 300).setStage(new Counter());
          }
        }
        """);
    project = ScratchProject.open(root);
    project.settings().startStage = "Counter";
    project.settings().save(root);
    Path java = Path.of(System.getProperty("java.home"), "bin", "java");

    Path index = WebPage.export(ScratchProject.open(root), Path.of(jar), java, root.resolve("export/web"));

    assertThat(index.resolveSibling("game.wasm")).exists();
    assertThat(Files.readString(root.resolve(".scratch4j/build/web/launcher/Scratch4JWebMain.java")))
        .contains("NrwList.use(").contains("new List<Object>()").contains("Counter.main(args);");
  }
}
