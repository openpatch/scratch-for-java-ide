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
    assertThat(SheetAnimationPanel.columns(70, 16)).isEqualTo(4);
  }

  @Test
  void aDragPicksTheRunAlongTheRowOrColumnItMostlyFollows() {
    // the boy's sheet: 4 columns, an animation runs down a column
    assertThat(SheetAnimationPanel.run(4, 0, 12)).containsExactly(0, 4, 8, 12);
    assertThat(SheetAnimationPanel.run(4, 13, 1)).containsExactly(13, 9, 5, 1);
    assertThat(SheetAnimationPanel.run(4, 4, 7)).containsExactly(4, 5, 6, 7);
    assertThat(SheetAnimationPanel.run(4, 7, 4)).containsExactly(7, 6, 5, 4);
    // a little off the line: still that column (or row)
    assertThat(SheetAnimationPanel.run(4, 5, 14)).containsExactly(5, 9, 13);
    assertThat(SheetAnimationPanel.run(4, 0, 7)).containsExactly(0, 1, 2, 3);
    assertThat(SheetAnimationPanel.run(4, 6, 6)).containsExactly(6);
  }

  @Test
  void readsFrameNumbersLeniently() {
    assertThat(SheetAnimationPanel.parseFrames("0, 4 8;12,,x")).containsExactly(0, 4, 8, 12);
    assertThat(SheetAnimationPanel.parseFrames("")).isEmpty();
  }

  @Test
  void writesTheLibrarysCallsForSheetsAndNumberedFiles() {
    assertThat(SheetAnimationPanel.code("idle", "assets/actor/animal/cat/sprite-sheet.png",
        16, 16, List.of(0, 1), 8))
        .isEqualTo("this.addAnimation(\"idle\", \"assets/actor/animal/cat/sprite-sheet.png\", "
            + "16, 16, 0, 1);\nthis.setAnimationInterval(0.125);");
    assertThat(FrameStrip.code("assets/images/walk%d.png", 4, 6))
        .isEqualTo("this.addAnimation(\"walk\", \"assets/images/walk%d.png\", 4);\n"
            + "this.setAnimationInterval(0.167);");
    assertThat(FrameStrip.code("assets/Run (%d).png", 2, 30))
        .isEqualTo("this.addAnimation(\"Run\", \"assets/Run (%d).png\", 2);\n"
            + "this.setAnimationInterval(0.033);");
  }

  @Test
  void readsThePreviewSpeedFromTheInterval() {
    assertThat(SheetAnimationPanel.fps("this.setAnimationInterval(0.125);")).isEqualTo(8);
    assertThat(SheetAnimationPanel.fps("this.setAnimationInterval( .2 );")).isEqualTo(5);
    assertThat(SheetAnimationPanel.fps("this.setAnimationInterval(0.001);")).isEqualTo(30);
    assertThat(SheetAnimationPanel.fps("this.setAnimationInterval(speed);")).isZero();
    assertThat(SheetAnimationPanel.fps("")).isZero();
  }

  @Test
  void offersTheAnimationsTheCodeMakesOfTheSheet() throws Exception {
    Path cat = root.resolve("Cat.java");
    Files.writeString(cat, """
        import org.openpatch.scratch.Sprite;

        public class Cat extends Sprite {
          public Cat() {
            this.addAnimation("idle", "assets/cat.png", 16, 16, 0, 1);
            this.setAnimationInterval(0.1);
            this.addAnimation("other", "assets/dog.png", 16, 16, 0);
            String sheet = "assets/cat.png";
            this.addAnimation("computed", sheet, 16, 16, 1);
          }
        }
        """);
    assertThat(SheetAnimationPanel.known(List.of(cat), "assets/cat.png")).containsExactly(
        new SheetAnimationPanel.Animation("idle", 16, 16, List.of(0, 1), 10));
  }
}
