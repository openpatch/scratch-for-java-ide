package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionMatchTest {

  @Test
  void prefixesFirstThenTheStartsOfWords() {
    assertThat(CompletionMatch.rank("move", "mo")).isZero();
    assertThat(CompletionMatch.rank("Math", "ma")).isEqualTo(1);
    assertThat(CompletionMatch.rank("getX", "gX")).isEqualTo(2);
    assertThat(CompletionMatch.rank("getX", "gx")).isEqualTo(2);
    assertThat(CompletionMatch.rank("switchCostumeTo", "swCo")).isEqualTo(2);
    assertThat(CompletionMatch.rank("switchCostumeTo", "sct")).isEqualTo(2);
    assertThat(CompletionMatch.rank("isTouchingSprite", "iTS")).isEqualTo(2);
    assertThat(CompletionMatch.rank("MAX_SPEED", "MS")).isEqualTo(2);
    assertThat(CompletionMatch.rank("HTTPServer", "hs")).isEqualTo(2);
  }

  @Test
  void theFirstLetterAndTheWordOrderMatter() {
    assertThat(CompletionMatch.rank("goToRandomPosition", "random"))
        .isEqualTo(CompletionMatch.NONE);
    assertThat(CompletionMatch.rank("getX", "Xg")).isEqualTo(CompletionMatch.NONE);
    assertThat(CompletionMatch.rank("move", "mx")).isEqualTo(CompletionMatch.NONE);
    // a word's middle is not a word start
    assertThat(CompletionMatch.rank("bounce", "bc")).isEqualTo(CompletionMatch.NONE);
  }

  @Test
  void recentlyPickedNamesComeFirstWithinTheirGroup() {
    List<String> names = new ArrayList<>(List.of("show", "setX", "say", "switchCostumeTo"));
    CompletionMatch.used("say");
    names.sort(CompletionMatch.order("s", Function.identity()));
    assertThat(names).containsExactly("say", "show", "setX", "switchCostumeTo");
    names.add("sizeX");
    names.removeIf(n -> CompletionMatch.rank(n, "sX") == CompletionMatch.NONE);
    names.sort(CompletionMatch.order("sX", Function.identity()));
    assertThat(names).containsExactly("setX", "sizeX");
  }
}
