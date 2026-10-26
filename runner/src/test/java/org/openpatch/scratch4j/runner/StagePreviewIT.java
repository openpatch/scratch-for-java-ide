package org.openpatch.scratch4j.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.ScratchProject;

/**
 * The designer's preview is the program's first frame: the tiled demo shows
 * its map and the sprites its code makes from it, and the sprites the designer
 * draws itself are left out. Needs a display - run under {@code xvfb-run -a}
 * with {@code -Dscratch4j.gltest=true}.
 */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class StagePreviewIT {

  @TempDir
  Path tmp;

  @Test
  void theTiledDemoShowsWhatItsCodeMakes() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", allJar);
    ScratchProject project = ScratchProject.open(root);

    var preview = StagePreview.make(project, project.startStage(), null, List.of("inventory"),
        ProgramRuntime.java(), Duration.ofSeconds(60));

    assertThat(preview.error()).isNull();
    assertThat(preview.stage()).isEqualTo("Level");
    assertThat(preview.width()).isEqualTo(640);
    assertThat(Files.size(preview.frame())).isPositive();
    Set<String> types = preview.sprites().stream().map(StagePreview.Sprite::type)
        .collect(Collectors.toSet());
    assertThat(types).contains("Player", "Item", "Wall", "Warp", "Water", "UIItem");
    var inventory = preview.sprites().stream().filter(s -> "inventory".equals(s.field()))
        .findFirst().orElseThrow();
    assertThat(inventory.omitted()).isTrue();
    // the last preview is kept for the stage selector
    assertThat(StagePreview.last(project, null)).isNotNull();
  }

  @Test
  void aRunLeavesThePictureForTheDesigner() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    Path root = BundledTemplates.create("demo-tiled", tmp, "running", allJar);
    ScratchProject project = ScratchProject.open(root);
    assertThat(ProjectRunner.captureOmit(project)).isEqualTo("Level:inventory");

    var handle = new ProjectRunner().run(project,
        RunConfig.of(project.startStage()).withExitAfter(4), new RunListener() { });
    handle.exitFuture().get(60, java.util.concurrent.TimeUnit.SECONDS);

    // the program ran as usual, and drew the world once into the designer's folder
    var picture = StagePreview.last(project, "Level");
    assertThat(picture).isNotNull();
    assertThat(picture.sprites()).extracting(StagePreview.Sprite::type).contains("Player");
    assertThat(picture.sprites()).filteredOn(s -> "inventory".equals(s.field()))
        .extracting(StagePreview.Sprite::omitted).containsExactly(true);
  }

  @Test
  void aStageThatNeedsArgumentsSaysSo() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled2", allJar);
    ScratchProject project = ScratchProject.open(root);

    var preview = StagePreview.make(project, project.startStage(), "Missing", List.of(),
        ProgramRuntime.java(), Duration.ofSeconds(60));

    assertThat(preview.ok()).isFalse();
    assertThat(preview.error()).contains("Missing");
  }
}
