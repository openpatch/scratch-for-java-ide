package org.openpatch.scratch4j.core.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssetCopierTest {

  @TempDir
  Path tmp;

  private Path projectRoot() throws Exception {
    NewProject.create(org.openpatch.scratch4j.core.project.ProjectTemplate.CLASSES_FIRST,
        tmp, "proj", null);
    return tmp.resolve("proj");
  }

  @Test
  void copiesBuiltinImageCroppedFromTheAtlas() throws Exception {
    Path root = projectRoot();
    BuiltinImage entry = BuiltinAssetIndex.get().image("bunny1_stand").orElseThrow();
    Path file = AssetCopier.copyBuiltinImage(entry, root);
    assertThat(file).isRegularFile();
    assertThat(file.getParent().toString()).contains("assets");
    BufferedImage image = ImageIO.read(file.toFile());
    assertThat(image.getWidth()).isEqualTo(entry.width());
    assertThat(image.getHeight()).isEqualTo(entry.height());
  }

  @Test
  void preRotatesCostumesDrawnFacingAnotherWay() throws Exception {
    Path root = projectRoot();
    BuiltinImage rotated = BuiltinAssetIndex.get().images().stream()
        .filter(m -> m.direction() != 90)
        .findFirst().orElseThrow();
    Path file = AssetCopier.copyBuiltinImage(rotated, root);
    BufferedImage image = ImageIO.read(file.toFile());
    // a 90-degree pre-rotation swaps width and height
    assertThat(image.getWidth()).isEqualTo(rotated.height());
    assertThat(image.getHeight()).isEqualTo(rotated.width());
  }

  @Test
  void uniqueNamesNeverOverwrite() throws Exception {
    Path root = projectRoot();
    BuiltinImage entry = BuiltinAssetIndex.get().image("bunny1_stand").orElseThrow();
    Path first = AssetCopier.copyBuiltinImage(entry, root);
    Path second = AssetCopier.copyBuiltinImage(entry, root);
    assertThat(first).isNotEqualTo(second);
    assertThat(first).isRegularFile();
    assertThat(second).isRegularFile();
  }

  @Test
  void copiesBuiltinSoundFile() throws Exception {
    Path root = projectRoot();
    Path file = AssetCopier.copyBuiltinSound("impactGlass_medium_001", root);
    assertThat(file).isRegularFile();
    assertThat(Files.size(file)).isGreaterThan(1000); // a real ogg, not empty

    assertThatThrownBy(() -> AssetCopier.copyBuiltinSound("no_such_sound", root))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("no_such_sound");
  }
}
