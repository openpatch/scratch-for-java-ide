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
    var world = model.byName("World");
    assertThat(world.superclass()).isEqualTo("Stage");
    assertThat(world.file()).isEqualTo(root.resolve("World.java"));
    assertThat(world.attributes()).extracting(ClassModel.Attribute::uml)
        .contains("- map: TiledMap", "- player: Player");
    assertThat(world.operations()).extracting(ClassModel.Operation::uml)
        .contains("+ World(mapFile: String, player: Player)",
            "+ whenKeyPressed(keyCode: KeyCode): void", "+ run(): void",
            "+ getPlayer(): Player");
    assertThat(model.byName("Stage").library()).isTrue();
    assertThat(model.byName("Bamboo").superclass()).isEqualTo("Enemy");
    assertThat(model.associations()).contains(
        new ClassModel.Association("World", "Player", "player", false));
  }
}
