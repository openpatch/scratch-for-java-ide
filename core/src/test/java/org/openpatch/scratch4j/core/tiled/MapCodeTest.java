package org.openpatch.scratch4j.core.tiled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.StageDocument;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapCodeTest {

  @TempDir
  Path tmp;

  @Test
  void mapGoesAfterTheSetupRegionWithItsImportAndCompiles() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "p", jar);
    Path stage = tmp.resolve("p/MyStage.java");
    String updated = MapCode.insert(Files.readString(stage), "assets/maps/level.tmx",
        List.of("Ground", "Water \"deep\""));
    assertThat(updated).contains("import org.openpatch.scratch.extensions.tiled.TiledMap;")
        .contains("    TiledMap map = new TiledMap(\"assets/maps/level.tmx\", this);\n"
            + "    map.stampLayerToBackground(\"Ground\");\n"
            + "    map.stampLayerToBackground(\"Water \\\"deep\\\"\");");
    // the designer's regions are untouched
    assertThat(StageDocument.read(updated).model())
        .isEqualTo(StageDocument.read(Files.readString(stage)).model());
    Files.writeString(stage, updated);
    var result = new CompilerService().compile(ScratchProject.open(tmp.resolve("p"))
        .javaSources(), List.of(jar), tmp.resolve("out"));
    assertThat(result.errors()).isEmpty();
    assertThatThrownBy(() -> MapCode.insert(updated, "assets/maps/level.tmx", List.of()))
        .hasMessageContaining("already");
    String second = MapCode.insert(updated, "assets/maps/other.tmx", List.of());
    assertThat(second).contains("TiledMap map2 = new TiledMap(\"assets/maps/other.tmx\", this);");
    assertThat(second.indexOf("map2")).isGreaterThan(second.indexOf("Water"));
  }
}
