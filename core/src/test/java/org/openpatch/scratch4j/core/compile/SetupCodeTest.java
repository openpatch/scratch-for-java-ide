package org.openpatch.scratch4j.core.compile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetupCodeTest {

  private static final String GAME = """
      public class Game extends Stage {
        private int lives = 3;
        private Player player;

        public Game() {
          player = new Player();
          this.add(player);
        }

        public void run() {
          player.move(2);
        }

        class Bonus {
          int points = 10;
        }
      }
      """;

  @Test
  void constructorsStartValuesAndNestedClassesCountMethodBodiesDoNot() {
    var before = SetupCode.of(GAME);
    assertThat(before).containsKeys("Game", "Game$Bonus");
    assertThat(SetupCode.changed(before, SetupCode.of(GAME.replace("move(2)", "move(5)"))))
        .isEmpty();
    assertThat(SetupCode.changed(before, SetupCode.of(GAME.replace(
        "this.add(player);", "this.add(player); // the hero\n    "))))
        .as("comments and formatting").isEmpty();
    assertThat(SetupCode.changed(before, SetupCode.of(GAME.replace(
        "this.add(player);", "this.add(player);\n    player.setX(100);"))))
        .containsExactly("Game");
    // a literal starting value is updated live, so it is no restart-only change
    assertThat(SetupCode.changed(before, SetupCode.of(GAME.replace("lives = 3", "lives = 5"))))
        .isEmpty();
    assertThat(SetupCode.changed(before, SetupCode.of(GAME.replace("lives = 3",
        "lives = 3 + 2")))).containsExactly("Game");
    var values = SetupCode.startValues(GAME.replace("private Player player;",
        "private Player player;\n  static double speed = -2.5;\n  final int max = 9;\n"
            + "  String name = \"Bob\";"));
    assertThat(values).containsOnlyKeys("Game.lives", "Game.speed", "Game.name",
        "Game$Bonus.points");
    assertThat(values.get("Game.speed")).isEqualTo(
        new SetupCode.StartValue("Game", "speed", "double", "-2.5", true));
    assertThat(values.get("Game.name").literal()).isEqualTo("\"Bob\"");
  }
}
