package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSpritesTest {

  @TempDir
  Path tmp;

  @Test
  void coinCollectorsGroundAndCoinsFromItsLoops() throws Exception {
    Path root = BundledTemplates.create("demo-coinCollector", tmp, "coins", null);
    var ghosts = CodeSprites.of(root, "CoinCollector");
    var coins = ghosts.stream().filter(g -> g.type().equals("Coin")).toList();
    var ground = ghosts.stream().filter(g -> g.type().equals("Ground")).toList();
    assertThat(ground).hasSize(13);       // x = -400, -336, ... < 400
    assertThat(coins).isNotEmpty();
    assertThat(coins.get(0).x()).isEqualTo(-240);
    assertThat(coins.get(0).y()).isEqualTo(-176 + 45);
    assertThat(coins.get(1).x()).isEqualTo(-240 + 112);
    assertThat(coins.get(0).line()).isGreaterThan(0);
  }

  @Test
  void raceStageRacersWithTheirArguments() throws Exception {
    Path root = BundledTemplates.create("red-light-green-light-100", tmp, "race", null);
    var ghosts = CodeSprites.of(root, "RaceStage");
    assertThat(ghosts).extracting(CodeSprites.Ghost::type).contains("Racer");
    assertThat(ghosts.stream().filter(g -> g.type().equals("Racer")))
        .extracting(CodeSprites.Ghost::y).containsExactly(60.0, 0.0, -60.0);
    // each racer looks like its own creature, not like the first one made
    assertThat(ghosts.stream().filter(g -> g.type().equals("Racer")))
        .extracting(CodeSprites.Ghost::costume).containsExactly("bee", "ladybug", "snail");
  }

  @Test
  void aClassWhoseCostumesComeFromItsParametersListsThemPerCreation() throws Exception {
    Path root = BundledTemplates.create("red-light-green-light-100", tmp, "race", null);
    assertThat(SpriteLook.variants(root, "Racer")).containsExactly(
        new SpriteLook.Variant("new Racer(\"bee\", 60, 2.2)", java.util.List.of("bee", "bee_move")),
        new SpriteLook.Variant("new Racer(\"ladybug\", 0, 1.6)",
            java.util.List.of("ladybug", "ladybug_move")),
        new SpriteLook.Variant("new Racer(\"snail\", -60, 1.0)",
            java.util.List.of("snail", "snail_move")));
    // a class made in one way only, with literal costumes
    assertThat(SpriteLook.variants(root, "Referee"))
        .extracting(SpriteLook.Variant::costumes).containsExactly(java.util.List.of("sign"));
  }

  @Test
  void costumesFollowJavasOrderThroughSuperclasses() throws Exception {
    java.nio.file.Files.writeString(tmp.resolve("Enemy.java"), """
        public class Enemy extends AnimatedSprite {
          public Enemy(String kind) {
            this.addCostume(kind);
            this.addCostume(kind + "_dead");
          }
        }
        """);
    java.nio.file.Files.writeString(tmp.resolve("Bee.java"), """
        public class Bee extends Enemy {
          public Bee() {
            super("bee");
            this.addCostume("bee_move");
          }
        }
        """);
    java.nio.file.Files.writeString(tmp.resolve("World.java"), """
        public class World extends Stage {
          public World() {
            this.add(new Bee());
          }
        }
        """);
    assertThat(SpriteLook.variants(tmp, "Bee")).extracting(SpriteLook.Variant::costumes)
        .containsExactly(java.util.List.of("bee", "bee_dead", "bee_move"));
  }
}
