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

  @Test
  void browserZipPreservesSourceAssetsAndMetadataWithoutBundledJars() throws Exception {
    ScratchProject project = project();
    project.settings().lesson = "roundtrip";
    Files.write(project.root().resolve("assets/image.png"), new byte[] {0, 1, 2, (byte)255});
    Files.writeString(project.root().resolve("identity.frag"), "shader source");
    Path archive = ProjectFormats.exportBrowserZip(project, tmp.resolve("out/browser.zip"));
    assertThat(Zip.readEntry(archive, "game/MyStage.java")).contains("class MyStage");
    assertThat(Zip.readEntry(archive, "game/identity.frag")).isEqualTo("shader source");
    assertThat(Zip.readEntry(archive, "game/.scratch4j/project.json")).contains("roundtrip", "libraryVersion", "startFile");
    try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
      assertThat(zip.stream().map(java.util.zip.ZipEntry::getName).toList()).noneMatch(name -> name.endsWith(".jar"));
    }
    Path imported = ProjectFormats.importZip(archive, tmp.resolve("imported"));
    assertThat(Files.readAllBytes(imported.resolve("assets/image.png"))).containsExactly(0, 1, 2, (byte)255);
    assertThat(ScratchProject.open(imported).settings().lesson).isEqualTo("roundtrip");
  }

  @Test
  void browserImportKeepsCourseProvidedDesktopSourcesUnchanged() throws Exception {
    Path archive = tmp.resolve("course.zip");
    String list = "/// School-provided course class\npublic class List<T> {}\n";
    try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
      var files = java.util.Map.of("List.java", list, "Main.java", "void main() {}\n",
          ".scratch4j/project.json", "{\"version\":1,\"sourceEnvironment\":\"browser\",\"flavour\":\"nrw\",\"desktopFiles\":[\"List.java\"]}");
      for (var entry : files.entrySet()) {
        zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    Path imported = ProjectFormats.importZip(archive, tmp.resolve("import"));
    assertThat(Files.readString(imported.resolve("List.java"))).isEqualTo(list);
    assertThat(Files.readString(imported.resolve("Main.java"))).contains("import org.openpatch.scratch.*;");
  }

  @Test
  void unsafeOrBrokenArchivesNeverPublishPartialProjects() throws Exception {
    Path archive = tmp.resolve("bad.zip");
    try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
      zip.putNextEntry(new java.util.zip.ZipEntry("game/Main.java"));
      zip.write("class Main {}".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new java.util.zip.ZipEntry("../escape.java"));
      zip.write("bad".getBytes(StandardCharsets.UTF_8));
    }
    org.assertj.core.api.Assertions.assertThatIOException().isThrownBy(() -> ProjectFormats.importZip(archive, tmp.resolve("imported")));
    assertThat(tmp.resolve("escape.java")).doesNotExist();
    assertThat(tmp.resolve("imported/game")).doesNotExist();
  }
}
