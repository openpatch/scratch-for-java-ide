package org.openpatch.scratch4j.core.uml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClassModelTest {

  @TempDir
  Path tmp;

  @Test
  void tiledDemoAsUml() throws Exception {
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", null);
    var model = ClassModel.of(ScratchProject.open(root).javaSources());
    var world = model.byName("Level");
    assertThat(world.superclass()).isEqualTo("Stage");
    assertThat(world.file()).isEqualTo(root.resolve("Level.java"));
    assertThat(world.attributes()).extracting(ClassModel.Attribute::uml)
        .contains("- map: TiledMap", "- player: Player");
    assertThat(world.operations()).extracting(ClassModel.Operation::uml)
        .contains("+ Level(mapFile: String, player: Player)",
            "+ whenKeyPressed(keyCode: KeyCode): void", "+ run(): void",
            "+ getPlayer(): Player");
    assertThat(model.byName("Stage").library()).isTrue();
    assertThat(model.byName("Bamboo").superclass()).isEqualTo("Enemy");
    assertThat(model.associations()).contains(
        new ClassModel.Association("Level", "Player", "player", false));
  }

  @Test
  void arraysOfAnyDimensionAreManyAssociations() throws Exception {
    Path world = tmp.resolve("World.java");
    java.nio.file.Files.writeString(world, """
        public class World extends Stage {
          private Item[][] grid = new Item[9][16];
          private Item[] row;
          private int[][] heights;
        }
        """);
    java.nio.file.Files.writeString(tmp.resolve("Item.java"), "public class Item {}");
    var model = ClassModel.of(java.util.List.of(world, tmp.resolve("Item.java")));
    assertThat(model.associations()).containsExactlyInAnyOrder(
        new ClassModel.Association("World", "Item", "grid", true),
        new ClassModel.Association("World", "Item", "row", true));
  }
}
