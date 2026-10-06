package org.openpatch.scratch4j.core.tiled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MapReferencesTest {

  @TempDir
  Path tmp;

  @Test
  void computedPathsOfferEveryMapMentionedOnesFirst() throws Exception {
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", null);
    String world = Files.readString(root.resolve("World.java"));
    assertThat(MapReferences.forStage(root, world)).extracting(p -> p.getFileName().toString())
        .containsExactly("Level1.tmx", "Level2.tmx");
    assertThat(MapReferences.forStage(root, "new TiledMap(\"Level2.tmx\", this);"))
        .extracting(p -> p.getFileName().toString()).containsExactly("Level2.tmx");
    assertThat(MapReferences.forStage(root, "class A extends Stage {}")).isEmpty();
  }
}
