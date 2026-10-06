package org.openpatch.scratch4j.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFormatsTest {

  @TempDir
  Path tmp;

  private ScratchProject project() throws IOException {
    // the all-jar: student projects carry the self-contained library
    // (the Central jar is slim - processing is not inside)
    Path jar = org.openpatch.scratch4j.runner.LibraryJarSource.allJar(
        tmp.resolve("bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "game", jar);
    return ScratchProject.open(tmp.resolve("game"));
  }

  @Test
  void runnableJarCarriesTheLauncherManifestAndClasses() throws IOException {
    ScratchProject project = project();
    Files.writeString(project.root().resolve("blobby.frag"), "shader source");
    Files.writeString(project.root().resolve("assets/images/cat.png"), "image data");
    Path jar = ProjectFormats.exportRunnableJar(project,
        tmp.resolve("out/game.jar"));
    assertThat(jar).isRegularFile();
    assertThat(Files.size(jar)).isGreaterThan(1_000_000); // the library is inside

    String manifest = Zip.readEntry(jar, "META-INF/MANIFEST.MF");
    assertThat(manifest).contains("Main-Class: Scratch4JLauncher");
    assertThat(Zip.readEntry(jar, "MyStage.class")).isNotNull();
    assertThat(Zip.readEntry(jar, "Player.class")).isNotNull();
    // the whole library jar is unpacked in
    assertThat(Zip.readEntry(jar, "org/openpatch/scratch/Sprite.class")).isNotNull();
    assertThat(Zip.readEntry(jar, "processing/core/PApplet.class")).isNotNull();
    assertThat(Zip.readEntry(jar, "blobby.frag")).isEqualTo("shader source");
    assertThat(Zip.readEntry(jar, "assets/images/cat.png")).isEqualTo("image data");
  }

  @Test
  void runnableJarStartsWithoutCommandLineArguments() throws Exception {
    ScratchProject project = project();
    Files.writeString(project.root().resolve("MyWindow.java"), """
        public class MyWindow extends org.openpatch.scratch.Window {
          public MyWindow() { super(480, 360); }
          public static void main(String[] args) {
            System.out.println("JAR_STARTED");
          }
        }
        """);
    Path jar = ProjectFormats.exportRunnableJar(project, tmp.resolve("out/game.jar"));

    Process process = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-jar", jar.toString())
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
    assertThat(output).contains("JAR_STARTED");
  }

  @Test
  void blueJAndVsCodeZipsOpenInThoseTools() throws IOException {
    ScratchProject project = project();
    Path bluej = ProjectFormats.exportBlueJZip(project,
        tmp.resolve("out/game-bluej.zip"));
    assertThat(Zip.readEntry(bluej, "game/package.bluej")).isNotNull();
    assertThat(Zip.readEntry(bluej, "game/MyStage.java")).isNotNull();

    Path vscode = ProjectFormats.exportVsCodeZip(project,
        tmp.resolve("out/game-vscode.zip"));
    String settings = Zip.readEntry(vscode, "game/.vscode/settings.json");
    assertThat(settings).contains("\"java.project.referencedLibraries\"");
  }

  @Test
  void zipRoundTripPreservesFiles() throws IOException {
    Path dir = tmp.resolve("roundtrip");
    Files.createDirectories(dir.resolve("assets/sounds"));
    Files.writeString(dir.resolve("MyStage.java"), "class X {}", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("assets/sounds/beep.wav"), "beep");
    Path zip = Zip.zipDirectory(dir, tmp.resolve("roundtrip.zip"));

    Path unpacked = tmp.resolve("unpacked");
    Zip.unzip(zip, unpacked);
    assertThat(unpacked.resolve("MyStage.java")).hasContent("class X {}");
    assertThat(unpacked.resolve("assets/sounds/beep.wav")).hasContent("beep");
  }
}
