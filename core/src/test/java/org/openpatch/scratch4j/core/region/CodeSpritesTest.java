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
  }
}
