package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DidYouMeanTest {

  private static final List<String> NAMES =
      List.of("move", "mouse", "turnLeft", "turnRight", "score", "setSize", "say");

  @Test
  void swappedLettersAndTyposFindTheName() {
    assertThat(DidYouMean.suggest("mvoe", NAMES, 3)).first().isEqualTo("move");
    assertThat(DidYouMean.suggest("turnleft", NAMES, 3)).first().isEqualTo("turnLeft");
    assertThat(DidYouMean.suggest("scroe", NAMES, 3)).containsExactly("score");
    assertThat(DidYouMean.suggest("setSzie", NAMES, 3)).containsExactly("setSize");
  }

  @Test
  void farAwayOrTinyWordsGetNothing() {
    assertThat(DidYouMean.suggest("banana", NAMES, 3)).isEmpty();
    assertThat(DidYouMean.suggest("x", NAMES, 3)).isEmpty();
    assertThat(DidYouMean.suggest("move", NAMES, 3)).doesNotContain("move");
  }

  @Test
  void distanceCountsATranspositionOnce() {
    assertThat(DidYouMean.distance("mvoe", "move")).isEqualTo(1);
    assertThat(DidYouMean.distance("kitten", "sitting")).isEqualTo(3);
  }
}
