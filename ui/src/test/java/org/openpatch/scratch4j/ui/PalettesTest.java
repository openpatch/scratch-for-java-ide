package org.openpatch.scratch4j.ui;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.assets.GimpPalette;
import org.openpatch.scratch4j.core.project.ProjectSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PalettesTest {

  @TempDir
  Path root;

  @Test
  void offersTheBuiltInPalettesThenTheProjects() throws Exception {
    new GimpPalette("Mine", 0, List.of(new GimpPalette.Entry(1, 2, 3, "a")))
        .write(root.resolve("assets/palettes/mine.gpl"));
    Files.writeString(root.resolve("assets/palettes/notes.txt"), "not a palette");
    assertThat(Palettes.available(root)).extracting(Palettes.Ref::id).containsExactly(
        "builtin:scratch", "builtin:pico-8", "builtin:greyscale", "assets/palettes/mine.gpl");
    assertThat(Palettes.available(null)).hasSize(3);
    assertThat(Palettes.load(root, "assets/palettes/mine.gpl").entries()).hasSize(1);
  }

  @Test
  void everyBuiltInPaletteLoads() {
    assertThat(Palettes.load(null, "builtin:scratch").entries()).hasSize(12);
    assertThat(Palettes.load(null, "builtin:pico-8").entries()).hasSize(16);
    assertThat(Palettes.load(null, "builtin:greyscale").entries()).hasSize(9);
  }

  @Test
  void aMissingOrBrokenPaletteFallsBackToTheScratchColours() throws Exception {
    Files.createDirectories(root.resolve("assets/palettes"));
    Files.writeString(root.resolve("assets/palettes/broken.gpl"), "JASC-PAL\n");
    assertThat(Palettes.load(root, "assets/palettes/broken.gpl").name()).isEqualTo("Scratch");
    assertThat(Palettes.load(root, "assets/palettes/gone.gpl").name()).isEqualTo("Scratch");
    assertThat(Palettes.load(root, "builtin:nope").name()).isEqualTo("Scratch");
  }

  @Test
  void theProjectDefaultLivesInProjectJsonAndNeedsAnExistingPalette() throws Exception {
    assertThat(Palettes.projectDefault(root)).isEqualTo(Palettes.SCRATCH);
    Palettes.setProjectDefault(root, "builtin:pico-8");
    assertThat(ProjectSettings.load(root).palette).isEqualTo("builtin:pico-8");
    assertThat(Palettes.projectDefault(root)).isEqualTo("builtin:pico-8");
    assertThat(Palettes.current(root)).isEqualTo("builtin:pico-8");

    Palettes.choose(root, "builtin:greyscale");
    assertThat(Palettes.current(root)).isEqualTo("builtin:greyscale");

    Palettes.setProjectDefault(root, "assets/palettes/deleted.gpl");
    assertThat(Palettes.projectDefault(root)).isEqualTo(Palettes.SCRATCH);
    Palettes.setProjectDefault(root, Palettes.SCRATCH);
    assertThat(ProjectSettings.load(root).palette).isEmpty();
  }

  @Test
  void addingToABuiltInPaletteCopiesItIntoTheProject() throws Exception {
    String id = Palettes.add(root, "builtin:pico-8", Color.rgb(10, 20, 30, 0.5));
    assertThat(id).isEqualTo("assets/palettes/pico-8.gpl");
    GimpPalette copy = GimpPalette.read(root.resolve(id));
    assertThat(copy.name()).isEqualTo("PICO-8");
    assertThat(copy.entries()).hasSize(17).last()
        .isEqualTo(new GimpPalette.Entry(10, 20, 30, ""));

    assertThat(Palettes.add(root, "builtin:pico-8", Color.WHITE))
        .isEqualTo("assets/palettes/pico-8-2.gpl");
    assertThat(Palettes.add(root, id, Color.WHITE)).isEqualTo(id);
    assertThat(GimpPalette.read(root.resolve(id)).entries()).hasSize(18);
  }

  @Test
  void remembersTheLastEightCustomColoursNewestFirst() {
    Path project = root.resolve("recent");
    for (int i = 0; i < 10; i++) {
      Palettes.remember(project, Color.gray(i / 10.0));
    }
    Palettes.remember(project, Color.gray(0.5));
    List<Color> recent = Palettes.recent(project);
    assertThat(recent).hasSize(8).first().isEqualTo(Color.gray(0.5));
    assertThat(recent).doesNotHaveDuplicates().doesNotContain(Color.gray(0.0), Color.gray(0.1));
  }
}
