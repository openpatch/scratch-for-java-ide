package org.openpatch.scratch4j.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisualModeTest {

  @TempDir Path dir;

  @Test
  void detectsClassesThatHaveAVisualEditor() throws Exception {
    Path stage = write("MyStage.java", "public class MyStage extends Stage { }");
    Path sprite = write("Cat.java", "public class Cat extends AnimatedSprite { }");
    Path other = write("Helper.java", "public class Helper { }");
    // a nested stage class does not make the file's own class a stage
    Path nested = write("Game.java",
        "public class Game { static class Level extends Stage { } }");

    assertTrue(VisualMode.isStageSource(stage));
    assertFalse(VisualMode.isSpriteSource(stage));
    assertTrue(VisualMode.isSpriteSource(sprite));
    assertFalse(VisualMode.isStageSource(sprite));
    assertFalse(VisualMode.isStageSource(other));
    assertFalse(VisualMode.isSpriteSource(other));
    assertFalse(VisualMode.isStageSource(nested));
    assertFalse(VisualMode.isStageSource(dir.resolve("missing.java")));
  }

  private Path write(String name, String text) throws Exception {
    return Files.writeString(dir.resolve(name), text);
  }
}
