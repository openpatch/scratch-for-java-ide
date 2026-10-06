package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Creates new projects from the built-in templates. Every generated project is
 * a plain folder that runs with nothing but the jar in {@code +libs} — the IDE
 * metadata in {@code .scratch4j/} is optional.
 */
public final class NewProject {

  private NewProject() {}

  /** Marker comment the designer owns (see {@code core.region.RegionParser}). */
  static final String MANAGED = " (managed by the stage designer)";

  public static void create(ProjectTemplate template, Path parentDir, String name,
      Path libraryJar) throws IOException {
    Path root = parentDir.resolve(name);
    if (Files.exists(root)) {
      throw new IOException("Folder already exists: " + root);
    }
    Files.createDirectories(root);
    switch (template) {
      case IMPERATIVE -> writeImperative(root, name);
      case CLASSES_FIRST -> writeClassesFirst(root, name);
      case BLUEJ_STARTER -> writeBluejStarter(root, name);
      case VSCODE_STARTER -> writeVsCodeStarter(root, name);
    }
    if (libraryJar != null) {
      Path libs = root.resolve("+libs");
      Files.createDirectories(libs);
      Files.copy(libraryJar, libs.resolve(libraryJar.getFileName()),
          StandardCopyOption.REPLACE_EXISTING);
    }
    Files.createDirectories(root.resolve("assets/images"));
    Files.createDirectories(root.resolve("assets/sounds"));
    // the program's entry point: window settings and the first stage
    write(root.resolve("MyWindow.java"), org.openpatch.scratch4j.core.region.WindowDocument
        .create("MyWindow", new org.openpatch.scratch4j.core.region.WindowDocument.Settings(
            480, 360, "MyStage", false, false, "", false)));
    ProjectSettings settings = new ProjectSettings();
    settings.startStage = "MyWindow";
    settings.save(root);
    write(root.resolve("README.md"), readme(name));
  }

  private static void writeImperative(Path root, String name) throws IOException {
    write(root.resolve("MyStage.java"), """
        import org.openpatch.scratch.KeyCode;
        import org.openpatch.scratch.Sprite;
        import org.openpatch.scratch.Stage;

        // One stage class steers plain sprites (imperative approach).

        public class MyStage extends Stage {

          // scratch4j:begin fields%s
          // scratch4j:end fields

          public MyStage() {
            super(480, 360);
            this.addBackdrop("background");

            // scratch4j:begin setup%s
            // scratch4j:end setup
          }

          public void run() {
          }

          public static void main(String[] args) {
            new MyStage();
          }
        }
        """.formatted(MANAGED, MANAGED));
  }

  private static void writeClassesFirst(Path root, String name) throws IOException {
    write(root.resolve("MyStage.java"), """
        import org.openpatch.scratch.Stage;

        // One Sprite subclass per sprite (classes-first approach).

        public class MyStage extends Stage {

          // scratch4j:begin fields%s
          Player player;
          // scratch4j:end fields

          public MyStage() {
            super(480, 360);

            // scratch4j:begin setup%s
            this.addBackdrop("background");
            player = new Player();
            this.add(player);
            // scratch4j:end setup
          }

          public void run() {
          }

          public static void main(String[] args) {
            new MyStage();
          }
        }
        """.formatted(MANAGED, MANAGED));
    write(root.resolve("Player.java"), """
        import org.openpatch.scratch.KeyCode;
        import org.openpatch.scratch.Sprite;

        public class Player extends Sprite {

          public Player() {
            this.addCostume("bunny1_stand");

            // scratch4j:begin setup%s
            this.setSize(50);
            // scratch4j:end setup
          }

          public void run() {
            if (this.isKeyPressed(KeyCode.RIGHT)) {
              this.setDirection(90);
              this.move(4);
            }
            if (this.isKeyPressed(KeyCode.LEFT)) {
              this.setDirection(-90);
              this.move(4);
            }
            this.ifOnEdgeBounce();
          }
        }
        """.formatted(MANAGED));
  }

  private static void writeBluejStarter(Path root, String name) throws IOException {
    writeClassesFirst(root, name);
    write(root.resolve("package.bluej"), """
        #BlueJ package file
        """);
  }

  private static void writeVsCodeStarter(Path root, String name) throws IOException {
    writeClassesFirst(root, name);
    write(root.resolve(".vscode/settings.json"), """
        {
          "java.project.referencedLibraries": ["+libs/*.jar"]
        }
        """);
  }

  private static String readme(String name) {
    return "# " + name + "\n\nA Scratch for Java project. Run MyWindow (see the tutorials at\n"
        + "https://scratch4j.openpatch.org).\n";
  }

  static void write(Path file, String content) throws IOException {
    if (content.indexOf('\r') >= 0) {
      throw new IOException("CRLF in template");
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Utility used by tests: resolves the jar of a class currently on the classpath. */
  public static Path classpathJar(Class<?> anchor) throws IOException {
    var location = anchor.getProtectionDomain().getCodeSource().getLocation();
    if (location == null) {
      throw new IOException("Cannot locate jar for " + anchor);
    }
    try {
      return Path.of(location.toURI());
    } catch (java.net.URISyntaxException e) {
      throw new IOException("Cannot locate jar for " + anchor, e);
    }
  }
}
