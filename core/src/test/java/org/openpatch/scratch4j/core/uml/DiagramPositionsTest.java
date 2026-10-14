package org.openpatch.scratch4j.core.uml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagramPositionsTest {

  @TempDir
  Path tmp;

  @Test
  void keepsWhereTheClassesWereMoved() throws Exception {
    assertThat(DiagramPositions.load(tmp)).isEmpty();
    Map<String, double[]> positions = new LinkedHashMap<>();
    positions.put("Player", new double[] {20.4, 140.6});
    positions.put("World", new double[] {260, 140});
    DiagramPositions.save(tmp, positions);

    Map<String, double[]> loaded = DiagramPositions.load(tmp);
    assertThat(loaded).containsOnlyKeys("Player", "World");
    assertThat(loaded.get("Player")).containsExactly(20, 141);
    assertThat(Files.readString(tmp.resolve(".scratch4j/diagram.json")))
        .contains("\"version\"", "\"Player\"");
  }

  @Test
  void noPositionsRemovesTheFile() throws Exception {
    DiagramPositions.save(tmp, Map.of("Player", new double[] {1, 2}));
    DiagramPositions.save(tmp, Map.of());
    assertThat(tmp.resolve(".scratch4j/diagram.json")).doesNotExist();
  }

  @Test
  void aBrokenFileArrangesTheDiagramAgain() throws Exception {
    Files.createDirectories(tmp.resolve(".scratch4j"));
    Files.writeString(tmp.resolve(".scratch4j/diagram.json"), "{ not json");
    assertThat(DiagramPositions.load(tmp)).isEmpty();
  }
}
