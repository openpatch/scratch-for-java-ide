package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.sound.SoundIO;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectAssetCreatorTest {

  @TempDir Path root;

  @Test
  void createsEditableImageSoundAndShadersInProject() throws Exception {
    Path image = ProjectAssetCreator.create(root, ProjectAssetCreator.Kind.IMAGE,
        "hero", 32, 48);
    Path sound = ProjectAssetCreator.create(root, ProjectAssetCreator.Kind.SOUND,
        "intro", 1, 1);
    Path fragment = ProjectAssetCreator.create(root,
        ProjectAssetCreator.Kind.FRAGMENT_SHADER, "glow", 1, 1);
    Path vertex = ProjectAssetCreator.create(root,
        ProjectAssetCreator.Kind.VERTEX_SHADER, "move", 1, 1);

    var png = ImageIO.read(image.toFile());
    assertThat(png.getWidth()).isEqualTo(32);
    assertThat(png.getHeight()).isEqualTo(48);
    assertThat(png.getRGB(0, 0) >>> 24).isZero();
    assertThat(SoundIO.decode(sound).sampleCount()).isEqualTo(44_100);
    assertThat(Files.readString(fragment)).contains("PROCESSING_TEXTURE_SHADER",
        "gl_FragColor");
    assertThat(Files.readString(vertex)).contains("transformMatrix", "gl_Position");
    assertThat(SyntaxHighlighter.languageOf(fragment))
        .isEqualTo(SyntaxHighlighter.Language.GLSL);
  }

  @Test
  void refusesOverwriteAndPathLikeNames() throws Exception {
    Path image = ProjectAssetCreator.create(root, ProjectAssetCreator.Kind.IMAGE,
        "hero", 16, 16);
    assertThatThrownBy(() -> ProjectAssetCreator.create(root,
        ProjectAssetCreator.Kind.IMAGE, "hero", 32, 32))
        .isInstanceOf(java.io.IOException.class);
    assertThat(ImageIO.read(image.toFile()).getWidth()).isEqualTo(16);
    assertThatThrownBy(() -> ProjectAssetCreator.create(root,
        ProjectAssetCreator.Kind.SOUND, "../outside", 1, 1))
        .isInstanceOf(java.io.IOException.class);
  }
}
