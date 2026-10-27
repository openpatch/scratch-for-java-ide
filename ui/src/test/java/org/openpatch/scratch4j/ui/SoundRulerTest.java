package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SoundRulerTest {

  @Test
  void stepLeavesRoomForTheLabels() {
    assertThat(SoundEditorView.rulerStep(10, 700)).isEqualTo(1);
    assertThat(SoundEditorView.rulerStep(2.3, 900)).isEqualTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(SoundEditorView.rulerStep(300, 800)).isEqualTo(50);
  }

  @Test
  void labelsUseTheStepsDecimals() {
    assertThat(SoundEditorView.rulerLabel(0, 1)).isEqualTo("0 s");
    assertThat(SoundEditorView.rulerLabel(1.5, 0.5)).isEqualTo("1.5 s");
    assertThat(SoundEditorView.rulerLabel(0.25, 0.05)).isEqualTo("0.25 s");
    assertThat(SoundEditorView.rulerLabel(90, 10)).isEqualTo("1:30");
  }
}
