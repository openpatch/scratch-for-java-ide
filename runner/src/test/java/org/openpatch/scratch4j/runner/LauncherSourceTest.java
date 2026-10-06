package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ProjectSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LauncherSourceTest {

  @TempDir Path root;

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
    assertThat(launcher.indexOf("TextureSampling.POINT")) .isLessThan(create);
    assertThat(launcher.indexOf("Window.useSplashLogo(\"assets/a\\\"b.png\")"))
        .isLessThan(create);
    assertThat(launcher.indexOf("setDebug(true)")) .isGreaterThan(create);

    Path file = root.resolve("Scratch4JLauncher.java");
    Path window = root.resolve("org/openpatch/scratch/Window.java");
    Path sampling = root.resolve("org/openpatch/scratch/TextureSampling.java");
    Files.createDirectories(window.getParent());
    Files.writeString(file, launcher);
    Files.writeString(window, """
        package org.openpatch.scratch;
        public class Window {
          public static Window getInstance() { return new Window(); }
          public static void useFullScreen() {}
          public static void useTextureSampling(TextureSampling value) {}
          public static void useSplashLogo(String path) {}
          public void setDebug(boolean value) {}
          public Stage getStage() { return null; }
          public void exit() {}
        }
        """);
    Files.writeString(sampling, """
        package org.openpatch.scratch;
        public enum TextureSampling { POINT }
        """);
    assertThat(new CompilerService().compile(List.of(file, window, sampling), List.of(),
        root.resolve("classes")).success()).isTrue();
  }
}
