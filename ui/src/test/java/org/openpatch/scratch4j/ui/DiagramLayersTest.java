package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagramLayersTest {

  @Test
  void subclassesHangBelowTheirParent() {
    Map<String, Integer> layers = DiagramLayers.of(List.of("Sprite", "Enemy", "Bamboo", "Stage"),
        Map.of("Enemy", "Sprite", "Bamboo", "Enemy"), List.of());
    assertThat(layers).containsEntry("Sprite", 0).containsEntry("Enemy", 1)
        .containsEntry("Bamboo", 2).containsEntry("Stage", 0);
  }

  @Test
  void aClassWithoutParentHangsBelowTheClassesHoldingIt() {
    Map<String, Integer> layers = DiagramLayers.of(List.of("Card", "Game", "Player"), Map.of(),
        List.of(new String[] {"Game", "Player"}, new String[] {"Player", "Card"},
            new String[] {"Game", "Card"}));
    assertThat(layers).containsEntry("Game", 0).containsEntry("Player", 1)
        .containsEntry("Card", 2);
  }

  @Test
  void aCircleStartsAtTheClassHoldingTheMost() {
    // the board knows its game back: the game, holding more, stays on top
    Map<String, Integer> layers = DiagramLayers.of(List.of("Board", "Game", "Die"), Map.of(),
        List.of(new String[] {"Board", "Game"}, new String[] {"Game", "Board"},
            new String[] {"Game", "Die"}, new String[] {"Board", "Board"}));
    assertThat(layers).containsEntry("Game", 0).containsEntry("Board", 1)
        .containsEntry("Die", 1);
  }

  @Test
  void associationsDoNotMoveSubclassesAwayFromTheirParent() {
    // the player is a sprite: it stays one below Sprite even though World holds it
    Map<String, Integer> layers = DiagramLayers.of(List.of("Sprite", "Stage", "Player", "World"),
        Map.of("Player", "Sprite", "World", "Stage"),
        List.<String[]>of(new String[] {"World", "Player"}));
    assertThat(layers).containsEntry("Player", 1).containsEntry("World", 1);
  }

  @Test
  void aCircleThroughASubclassStillEnds() {
    Map<String, Integer> layers = DiagramLayers.of(List.of("Player", "Bank", "Field"),
        Map.of("Bank", "Player"),
        List.of(new String[] {"Bank", "Player"}, new String[] {"Player", "Field"},
            new String[] {"Field", "Player"}));
    assertThat(layers).containsEntry("Player", 0).containsEntry("Bank", 1)
        .containsEntry("Field", 1);
  }
}
