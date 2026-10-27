package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SheetAnimationPanelTest {

  @TempDir
  Path root;

  @Test
  void guessesSquareTilesFromTheSheetsShape() {
    assertThat(SheetGridDialog.guessTile(32, 16)).containsExactly(16, 16);
    assertThat(SheetGridDialog.guessTile(16, 64)).containsExactly(16, 16);
    assertThat(SheetGridDialog.guessTile(128, 32)).containsExactly(32, 32);
    assertThat(SheetGridDialog.guessTile(96, 64)).containsExactly(32, 32);
    assertThat(SheetGridDialog.guessTile(50, 16)).containsExactly(50, 16);
    assertThat(SheetAnimationPanel.firstRow(64, 16)).containsExactly(0, 1, 2, 3);
    assertThat(SheetAnimationPanel.columns(70, 16)).isEqualTo(4);
  }

  @Test
  void readsFrameNumbersLeniently() {
    assertThat(SheetAnimationPanel.parseFrames("0, 4 8;12,,x")).containsExactly(0, 4, 8, 12);
    assertThat(SheetAnimationPanel.parseFrames("")).isEmpty();
  }

  @Test
  void writesTheLibrarysCallsForSheetsAndNumberedFiles() {
    assertThat(SheetAnimationPanel.code("idle", "assets/actor/animal/cat/sprite-sheet.png",
        16, 16, List.of(0, 1)))
        .isEqualTo("this.addAnimation(\"idle\", \"assets/actor/animal/cat/sprite-sheet.png\", "
            + "16, 16, 0, 1);");
    assertThat(FrameStrip.code("assets/images/walk%d.png", 4))
        .isEqualTo("this.addAnimation(\"walk\", \"assets/images/walk%d.png\", 4);");
    assertThat(FrameStrip.code("assets/Run (%d).png", 2))
        .isEqualTo("this.addAnimation(\"Run\", \"assets/Run (%d).png\", 2);");
  }

  @Test
  void offersTheAnimationsTheCodeMakesOfTheSheet() throws Exception {
    Path cat = root.resolve("Cat.java");
    Files.writeString(cat, """
        import org.openpatch.scratch.Sprite;

        public class Cat extends Sprite {
          public Cat() {
            this.addAnimation("idle", "assets/cat.png", 16, 16, 0, 1);
            this.addAnimation("other", "assets/dog.png", 16, 16, 0);
            String sheet = "assets/cat.png";
            this.addAnimation("computed", sheet, 16, 16, 1);
          }
        }
        """);
    assertThat(SheetAnimationPanel.known(List.of(cat), "assets/cat.png")).containsExactly(
        new SheetAnimationPanel.Animation("idle", 16, 16, List.of(0, 1)));
  }
}
