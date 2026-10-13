package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch.RotationStyle;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.SpriteRef;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StageRendererTest {

  @Test
  void rotationStylesFollowRuntimeDirectionRules() {
    var right = new StageRenderer.Look(null, 100, 90, RotationStyle.LEFT_RIGHT);
    var left = new StageRenderer.Look(null, 100, -90, RotationStyle.LEFT_RIGHT);
    var fixed = new StageRenderer.Look(null, 100, -90, RotationStyle.DONT);
    var turning = new StageRenderer.Look(null, 100, -90, RotationStyle.ALL_AROUND);

    assertThat(right.mirrored()).isFalse();
    assertThat(left.mirrored()).isTrue();
    assertThat(left.rotationAngle()).isZero();
    assertThat(fixed.mirrored()).isFalse();
    assertThat(fixed.rotationAngle()).isZero();
    assertThat(turning.rotationAngle()).isEqualTo(-180);
  }

  @Test
  void aSpriteTheStageDoesNotPlaceStandsWhereThePreviewSawIt(@TempDir Path tmp)
      throws Exception {
    Path root = BundledTemplates.create("red-light-green-light-100", tmp, "race", null);
    var renderer = new StageRenderer(ScratchProject.open(root));
    SpriteRef referee = new SpriteRef("referee", "Referee");
    // without a preview: in the middle
    assertThat(renderer.position(referee)).containsExactly(0, 0);
    // the preview saw its constructor put it at (-250, 110)
    renderer.setPreview(new org.openpatch.scratch4j.runner.StagePreview.Result(
        tmp.resolve("frame.png"), "RaceStage", 480, 360, 0, 0, 100, java.util.List.of(
            new org.openpatch.scratch4j.runner.StagePreview.Sprite("Referee", "referee", -250,
                110, 40, 40, 90, 45, "referee", true, false, true)), null));
    assertThat(renderer.position(referee)).containsExactly(-250, 110);
    assertThat(renderer.look(referee).size()).isEqualTo(45);
    // the stage's own setPosition comes after the constructor's
    referee.setPosition(10, 20);
    assertThat(renderer.position(referee)).containsExactly(10, 20);
  }
}
