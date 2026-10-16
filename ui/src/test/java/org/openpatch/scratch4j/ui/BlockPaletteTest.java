package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.core.api.ApiIndex;

import static org.assertj.core.api.Assertions.assertThat;

class BlockPaletteTest {

  @Test
  void randomBlocksCallTheUtilityClass() {
    var index = ApiIndex.load();
    assertThat(index.byName("random")).filteredOn(method -> method.methodName().equals("random"))
        .extracting(BlockPalette::snippet)
        .containsExactlyInAnyOrder("Random.random()", "Random.random(max)", "Random.random(min, max)");
    assertThat(index.byName("randomInt")).extracting(BlockPalette::snippet)
        .containsExactlyInAnyOrder("Random.randomInt(max)", "Random.randomInt(min, max)");
    assertThat(index.byName("randomVector2")).extracting(BlockPalette::snippet)
        .containsExactly("Random.randomVector2()");
    assertThat(index.byName("move")).extracting(BlockPalette::snippet)
        .contains("this.move(steps);\n");
  }
}
