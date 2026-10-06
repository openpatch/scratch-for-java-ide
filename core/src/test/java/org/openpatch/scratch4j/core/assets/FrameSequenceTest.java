package org.openpatch.scratch4j.core.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameSequenceTest {

  @TempDir
  Path root;

  private String content(FrameSequence frames, int number) throws Exception {
    return Files.readString(frames.frame(number));
  }

  @Test
  void keepsFramesNumberedFromOneWithoutGaps() throws Exception {
    FrameSequence frames = new FrameSequence(root, "assets/images/walk%d.png");
    assertThat(frames.frames()).isEmpty();
    Path first = frames.addBlank(16, 8);
    assertThat(first).isEqualTo(root.resolve("assets/images/walk1.png"));
    assertThat(javax.imageio.ImageIO.read(first.toFile()).getWidth()).isEqualTo(16);
    Files.writeString(frames.frame(2), "B");
    Files.writeString(frames.frame(3), "C");
    Files.writeString(frames.frame(5), "gap: not part of the sequence");
    assertThat(frames.frames()).hasSize(3);
    Files.delete(frames.frame(5));

    frames.duplicate(2);
    assertThat(frames.frames()).hasSize(4);
    assertThat(content(frames, 2)).isEqualTo("B");
    assertThat(content(frames, 3)).isEqualTo("B");
    assertThat(content(frames, 4)).isEqualTo("C");

    frames.swap(1, 4);
    assertThat(content(frames, 1)).isEqualTo("C");

    Path trashed = frames.delete(2);
    assertThat(trashed).startsWith(root.resolve(".scratch4j/trash"));
    assertThat(frames.frames()).hasSize(3);
    assertThat(content(frames, 2)).isEqualTo("B");
    // the blank first frame went to the end by the swap
    assertThat(javax.imageio.ImageIO.read(frames.frame(3).toFile()).getWidth()).isEqualTo(16);
    assertThatThrownBy(() -> frames.delete(9)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FrameSequence(root, "walk.png"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
