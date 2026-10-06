package org.openpatch.scratch4j.core.tiled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language;
import org.openpatch.scratch4j.core.project.BundledTemplates;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MapLinterTest {

  @TempDir
  Path tmp;

  @Test
  void bundledDemoIsCleanAndWrongLayersAreFlagged() throws Exception {
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", null);
    Path world = root.resolve("World.java");
    String source = Files.readString(world);
    MapLinter linter = new MapLinter(Language.EN);
    assertThat(linter.lint(root, world, source)).isEmpty();

    String broken = source.replace("stampLayerToBackground(\"Walls\")",
        "stampLayerToBackground(\"walls\")").replace("getObjectsFromLayer(\"Objects\")",
        "getObjectsFromLayer(\"Floor\")");
    var findings = new MapLinter(Language.EN).lint(root, world, broken);
    assertThat(findings).extracting(MapLinter.Finding::message).containsExactlyInAnyOrder(
        "\"Floor\" is a tile layer, not an object layer.",
        "No tile layer \"walls\" in Level1.tmx, Level2.tmx.");
    assertThat(findings).filteredOn(f -> f.message().startsWith("No"))
        .flatExtracting(MapLinter.Finding::suggestions).contains("Walls");
  }

  @Test
  void literalMapsAreCheckedForExistenceAndFormat() throws Exception {
    Path root = Files.createDirectories(tmp.resolve("p"));
    Files.createDirectories(root.resolve("assets/maps"));
    Files.writeString(root.resolve("assets/maps/a.tmx"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <map version="1.10" orientation="orthogonal" width="1" height="1" tilewidth="8"
             tileheight="8" infinite="0">
          <layer id="1" name="ground" width="1" height="1">
            <data encoding="base64">AQAAAA==</data>
          </layer>
        </map>
        """);
    Path stage = root.resolve("MyStage.java");
    String source = """
        import org.openpatch.scratch.extensions.tiled.TiledMap;
        public class MyStage extends Stage {
          public MyStage() {
            TiledMap a = new TiledMap("assets/maps/a.tmx", this);
            TiledMap b = new TiledMap("assets/maps/missing.tmx", this);
            a.stampLayerToBackground("ground");
          }
        }
        """;
    var findings = new MapLinter(Language.DE).lint(root, stage, source);
    assertThat(findings).extracting(MapLinter.Finding::line).containsExactly(4L, 5L);
    assertThat(findings.get(0).message()).contains("a.tmx").contains("CSV");
    assertThat(findings.get(1).message()).contains("gibt es nicht");
  }
}
