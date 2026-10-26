package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates new Sprite / Stage classes in the library's documentation style:
 * 2-space indent, {@code this.} prefix, the block the stage designer or sprite
 * editor owns, Java 17 compatible. Scratch for Java 7 has no AnimatedSprite and
 * UISprite: an animated sprite is a Sprite with animations, a UI sprite a Sprite
 * on the UI layer; the wizard keeps offering both as starting points.
 */
public final class NewClass {

  /** The class kinds offered by the wizard. */
  public enum Kind { STAGE, SPRITE, ANIMATED_SPRITE, UI_SPRITE }

  private NewClass() {}

  /**
   * Writes {@code <ClassName>.java} into the project. Refuses names that are
   * not valid Java identifiers or that would overwrite an existing file.
   */
  public static Path create(Kind kind, Path projectRoot, String className) throws IOException {
    if (!className.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IOException("Not a valid Java class name: " + className);
    }
    Path file = projectRoot.resolve(className + ".java");
    if (Files.exists(file)) {
      throw new IOException("Class already exists: " + className);
    }
    Files.writeString(file, source(kind, className), java.nio.charset.StandardCharsets.UTF_8);
    return file;
  }

  private static String source(Kind kind, String name) {
    return switch (kind) {
      case STAGE -> """
          import org.openpatch.scratch.Stage;

          public class %s extends Stage {

            // begin stage designer
            // end stage designer

            public %s() {
              // begin stage designer
              // end stage designer
            }

            public void run() {
            }
          }
          """.formatted(name, name);
      case SPRITE -> """
          import org.openpatch.scratch.Sprite;

          public class %s extends Sprite {

            public %s() {
              this.addCostume("bunny1_stand");

              // begin sprite editor
              // end sprite editor
            }

            public void run() {
            }
          }
          """.formatted(name, name);
      case ANIMATED_SPRITE -> """
          import org.openpatch.scratch.Sprite;

          public class %s extends Sprite {

            public %s() {
              this.addAnimation("idle", "bunny1_walk%%d", 2);

              // begin sprite editor
              // end sprite editor
            }

            public void run() {
            }
          }
          """.formatted(name, name);
      case UI_SPRITE -> """
          import org.openpatch.scratch.Layer;
          import org.openpatch.scratch.Sprite;

          public class %s extends Sprite {

            public %s() {
              this.setLayer(Layer.UI);

              // begin sprite editor
              this.setWidth(100);
              this.setHeight(40);
              // end sprite editor
            }

            public void run() {
            }
          }
          """.formatted(name, name);
    };
  }
}
