package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch.RotationStyle;

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
}
