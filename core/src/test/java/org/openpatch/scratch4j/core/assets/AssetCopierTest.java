package org.openpatch.scratch4j.core.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        .filter(m -> m.direction() == 0 || m.direction() == 180)
        .findFirst().orElseThrow();
    Path file = AssetCopier.copyBuiltinImage(rotated, root);
    BufferedImage image = ImageIO.read(file.toFile());
    // a quarter turn swaps width and height
    assertThat(image.getWidth()).isEqualTo(rotated.height());
    assertThat(image.getHeight()).isEqualTo(rotated.width());
  }

  @Test
  void aCostumeDrawnFacingLeftIsMirroredNotTurnedUpsideDown() throws Exception {
    Path root = projectRoot();
    BuiltinImage bee = BuiltinAssetIndex.get().images().stream()
        .filter(m -> m.name().equals("bee")).findFirst().orElseThrow();
    assertThat(bee.direction()).isEqualTo(-90);
    BufferedImage copied = ImageIO.read(AssetCopier.copyBuiltinImage(bee, root).toFile());
    BufferedImage sheet;
    try (var in = AssetCopier.class.getResourceAsStream("/" + bee.sheetPath())) {
      sheet = ImageIO.read(in);
    }
    assertThat(copied.getWidth()).isEqualTo(bee.width());
    assertThat(copied.getHeight()).isEqualTo(bee.height());
    // pixel (x, y) of the copy is pixel (w - 1 - x, y) of the sheet: a mirror, rows stay
    for (int y = 0; y < bee.height(); y += 7) {
      for (int x = 0; x < bee.width(); x += 5) {
        assertThat(copied.getRGB(x, y))
            .isEqualTo(sheet.getRGB(bee.x() + bee.width() - 1 - x, bee.y() + y));
      }
    }
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

  @ParameterizedTest
  @ValueSource(strings = {"png", "JPG", "jpeg", "gif", "wav", "OGG", "mp3", "aif", "aiff", "au"})
  void importsExternalImagesAndSoundsWithoutChangingTheOriginal(String extension) throws Exception {
    Path root = projectRoot();
    Path source = tmp.resolve("Mein Bild und Klang \u00e4." + extension);
    byte[] content = {0, 1, 2, 3, -1};
    Files.write(source, content);

    Path copied = AssetCopier.copyExternalFile(source, root);

    boolean image = extension.matches("(?i)png|jpg|jpeg|gif");
    assertThat(copied).isEqualTo(root.resolve(image ? "assets/images" : "assets/sounds")
        .resolve(source.getFileName()));
    assertThat(Files.readAllBytes(copied)).isEqualTo(content);
    assertThat(Files.readAllBytes(source)).isEqualTo(content);
  }

  @Test
  void externalImportsKeepExistingAssetsAndChooseDistinctNames() throws Exception {
    Path root = projectRoot();
    Path existing = root.resolve("assets/images/cat.png");
    Files.writeString(existing, "existing");
    Path source = Files.writeString(tmp.resolve("cat.png"), "imported");

    Path second = AssetCopier.copyExternalFile(source, root);
    Path third = AssetCopier.copyExternalFile(source, root);

    assertThat(second).hasFileName("cat2.png");
    assertThat(third).hasFileName("cat3.png");
    assertThat(Files.readString(existing)).isEqualTo("existing");
    assertThat(Files.readString(second)).isEqualTo("imported");
    assertThat(Files.readString(third)).isEqualTo("imported");
    assertThat(source).hasContent("imported");
  }

  @Test
  void refusesDirectoriesMissingFilesAndUnsupportedFiles() throws Exception {
    Path root = projectRoot();
    Path directory = Files.createDirectory(tmp.resolve("folder.png"));
    Path unsupported = Files.writeString(tmp.resolve("notes.txt"), "notes");
    for (Path source : java.util.List.of(directory, unsupported, tmp.resolve("missing.wav"))) {
      assertThatThrownBy(() -> AssetCopier.copyExternalFile(source, root))
          .isInstanceOf(java.io.IOException.class);
    }
    try (var images = Files.list(root.resolve("assets/images"));
         var sounds = Files.list(root.resolve("assets/sounds"))) {
      assertThat(images).isEmpty();
      assertThat(sounds).isEmpty();
    }
  }
}
