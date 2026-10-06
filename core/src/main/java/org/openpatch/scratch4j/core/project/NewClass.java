package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates new Sprite / AnimatedSprite / UISprite / Stage classes in the
 * library's documentation style: 2-space indent, {@code this.} prefix, a
 * managed {@code setup} region for the designer, Java 17 compatible.
 */
public final class NewClass {

  /** The class kinds offered by the wizard. */
  public enum Kind { STAGE, SPRITE, ANIMATED_SPRITE, UI_SPRITE }

  /** Marker comment the designer owns (same as the templates). */
  private static final String MANAGED = " (managed by the stage designer)";

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

            // scratch4j:begin fields%s
            // scratch4j:end fields

            public %s() {
              super(480, 360);

              // scratch4j:begin setup%s
              // scratch4j:end setup
            }

            public void run() {
            }
          }
          """.formatted(name, MANAGED, name, MANAGED);
      case SPRITE -> """
          import org.openpatch.scratch.Sprite;

          public class %s extends Sprite {

            public %s() {
              this.addCostume("bunny1_stand");

              // scratch4j:begin setup%s
              // scratch4j:end setup
            }

            public void run() {
            }
          }
          """.formatted(name, name, MANAGED);
      case ANIMATED_SPRITE -> """
          import org.openpatch.scratch.AnimatedSprite;

          public class %s extends AnimatedSprite {

            public %s() {
              this.addAnimation("idle", "bunny1_walk%%d", 2);

              // scratch4j:begin setup%s
              // scratch4j:end setup
            }

            public void run() {
            }
          }
          """.formatted(name, name, MANAGED);
      case UI_SPRITE -> """
          import org.openpatch.scratch.UISprite;

          public class %s extends UISprite {

            public %s() {

              // scratch4j:begin setup%s
              this.setWidth(100);
              this.setHeight(40);
              // scratch4j:end setup
            }

            public void run() {
            }
          }
          """.formatted(name, name, MANAGED);
    };
  }
}
