package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Typing in the Files tree jumps to a name. */
class FileTreeSearchTest {

  private final List<String> names = List.of("assets", "images", "Cat.png", "bobcat.png",
      "Player.java", "cat-2.png");

  @Test
  void aNameThatStartsWithTheLettersWinsOverOneThatContainsThem() {
    assertThat(ProjectTree.match(names, 0, "cat")).isEqualTo(2);
    assertThat(ProjectTree.match(names, 3, "cat")).isEqualTo(5);
  }

  @Test
  void searchWrapsAroundAndFallsBackToContains() {
    assertThat(ProjectTree.match(names, 6, "CAT")).isEqualTo(2);
    assertThat(ProjectTree.match(names, 0, "bcat")).isEqualTo(3);
    assertThat(ProjectTree.match(names, 0, "dog")).isEqualTo(-1);
    assertThat(ProjectTree.match(List.of(), 0, "a")).isEqualTo(-1);
  }
}
