package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ProjectSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.openpatch.scratch4j.core.project.LibraryJarSource;

class LauncherSourceTest {

  @TempDir Path root;

  /** A Window without a window: what the generated launcher calls on it (Scratch for Java 7). */
  static final String STUB_WINDOW = """
      package org.openpatch.scratch;
      public class Window {
        public Window() {}
        public Window(int width, int height) {}
        public void setStage(Stage stage) {}
        public static Window getInstance() { return new Window(); }
        public static void use(org.openpatch.scratch.extension.Extension extension) {}
        public static void useFullScreen() {}
        public static void usePixelArt() {}
        public static void useSplashLogo(String path) {}
        public void setDebug(boolean value) {}
        public Stage getStage() { return null; }
        public void pause() {}
        public void resume() {}
        public void step() {}
        public void setGameSpeed(double speed) {}
        public void exit() { EXIT }
      }
      """;

  @Test
  void windowOptionsAreEmittedBeforeStageCreationAndDebugAfter() throws Exception {
    ProjectSettings settings = new ProjectSettings();
    settings.fullScreen = true;
    settings.pixelArt = true;
    settings.debugOnStart = true;
    settings.splashLogo = "assets/a\"b.png";
    String launcher = LauncherSource.generate(settings);

    int create = launcher.indexOf("Class<?> start = Class.forName(startClassName(args));");
    assertThat(launcher.indexOf("Window.useFullScreen()")) .isLessThan(create);
    assertThat(launcher.indexOf("Window.usePixelArt()")) .isLessThan(create);
    assertThat(launcher.indexOf("Window.useSplashLogo(\"assets/a\\\"b.png\")"))
        .isLessThan(create);
    assertThat(launcher.indexOf("setDebug(true)")) .isGreaterThan(create);

    Path file = root.resolve("Scratch4JLauncher.java");
    Path window = root.resolve("org/openpatch/scratch/Window.java");
    Files.createDirectories(window.getParent());
    Files.writeString(file, launcher);
    Files.writeString(window, STUB_WINDOW.replace("EXIT", ""));
    // the stub shadows the library's Window; everything else (extensions, recorder) is the library's
    Path jar = LibraryJarSource.allJar(root.resolve("bundled"));
    assertThat(new CompilerService().compile(List.of(file, window), List.of(jar),
        root.resolve("classes")).errors()).isEmpty();
  }

  @Test
  void controlledSmokeCountdownWaitsForTheFirstFrame() throws Exception {
    Path launcher = root.resolve("Scratch4JLauncher.java");
    Path main = root.resolve("Delayed.java");
    Path window = root.resolve("org/openpatch/scratch/Window.java");
    Path classes = root.resolve("classes");
    Files.createDirectories(window.getParent());
    Files.writeString(launcher, LauncherSource.generate());
    Files.writeString(main, """
        public class Delayed {
          public static void main(String[] args) throws Exception {
            Thread.sleep(2000);
            System.out.println("FIRST_FRAME");
            Scratch4JLauncher.IdeControl.frames = 1;
            Thread.sleep(10000);
          }
        }
        """);
    Files.writeString(window, STUB_WINDOW.replace("EXIT",
        "System.out.println(\"SMOKE_EXIT\"); System.exit(0);"));
    Path jar = LibraryJarSource.allJar(root.resolve("bundled"));
    assertThat(new CompilerService().compile(List.of(launcher, main, window), List.of(jar), classes)
        .errors()).isEmpty();
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Dscratch4j.ide.control=true", "-Dscratch4j.ide.exitAfter=1", "-cp",
        classes + java.io.File.pathSeparator + jar, "Scratch4JLauncher", "Delayed")
        .redirectErrorStream(true).start();
    try {
      assertThat(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(process.exitValue()).as(output).isZero();
      assertThat(output).contains("FIRST_FRAME", "SMOKE_EXIT");
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void launcherStartsJava25CompactSourceMain() throws Exception {
    Path launcher = root.resolve("Scratch4JLauncher.java");
    Path main = root.resolve("Main.java");
    Path classes = root.resolve("classes");
    Files.writeString(launcher, LauncherSource.generate(new ProjectSettings()));
    Files.writeString(main, "void main() { System.out.println(\"COMPACT_STARTED\"); }\n");
    Path jar = LibraryJarSource.allJar(root.resolve("bundled"));
    assertThat(new CompilerService().compile(List.of(launcher, main), List.of(jar), classes).success()).isTrue();
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", classes + java.io.File.pathSeparator + jar, "Scratch4JLauncher", "Main").redirectErrorStream(true).start();
    assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(output).contains("COMPACT_STARTED");
  }
}
