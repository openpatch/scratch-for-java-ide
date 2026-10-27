package org.openpatch.scratch4j.ui;

import org.openpatch.scratch4j.core.assets.GimpPalette;
import org.openpatch.scratch4j.core.io.AtomicFiles;
import org.openpatch.scratch4j.sound.SoundClip;
import org.openpatch.scratch4j.sound.SoundIO;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Creates editable project assets without depending on the JavaFX toolkit. */
final class ProjectAssetCreator {

  /** Scratch for Java 7 shaders are fragment shaders only. */
  enum Kind { IMAGE, SOUND, FRAGMENT_SHADER, PALETTE }

  private ProjectAssetCreator() {}

  static Path create(Path root, Kind kind, String name, int width, int height)
      throws IOException {
    if (!name.matches("[A-Za-z0-9_][A-Za-z0-9_ -]*")) {
      throw new IOException("Use letters, digits, spaces, hyphens or underscores for the name");
    }
    if (kind == Kind.IMAGE && (width < 1 || height < 1 || width > 4096 || height > 4096)) {
      throw new IOException("Image width and height must be between 1 and 4096 pixels");
    }
    String folder = switch (kind) {
      case IMAGE -> "assets/images";
      case SOUND -> "assets/sounds";
      case FRAGMENT_SHADER -> "assets/shaders";
      case PALETTE -> Palettes.FOLDER;
    };
    String extension = switch (kind) {
      case IMAGE -> ".png";
      case SOUND -> ".wav";
      case FRAGMENT_SHADER -> ".frag";
      case PALETTE -> GimpPalette.EXTENSION;
    };
    Path dir = root.resolve(folder);
    Path target = dir.resolve(name + extension);
    if (Files.exists(target)) {
      throw new IOException("Asset already exists: " + target.getFileName());
    }
    Files.createDirectories(dir);
    if (kind == Kind.FRAGMENT_SHADER) {
      AtomicFiles.writeString(target, FRAGMENT_TEMPLATE);
      return target;
    }
    if (kind == Kind.PALETTE) {
      // black and white to start from; the palette editor adds the rest
      AtomicFiles.writeString(target, new GimpPalette(name, 8, List.of(
          new GimpPalette.Entry(0, 0, 0, "black"),
          new GimpPalette.Entry(255, 255, 255, "white"))).format());
      return target;
    }
    Path temp = Files.createTempFile(dir, ".new-asset", extension);
    try {
      if (kind == Kind.IMAGE) {
        if (!ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB),
            "png", temp.toFile())) {
          throw new IOException("No PNG writer is available");
        }
      } else {
        SoundIO.writeWav(SoundClip.silence(1, 44_100, 44_100), temp);
      }
      if (Files.exists(target)) {
        throw new IOException("Asset already exists: " + target.getFileName());
      }
      AtomicFiles.replace(temp, target);
      return target;
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static final String FRAGMENT_TEMPLATE = """
      // costume is the picture being drawn, uv where on it this pixel is
      // ((0, 0) one corner, (1, 1) the opposite one), time the game time in
      // seconds. The pixel's colour goes into color.

      void main(void) {
        color = texture(costume, uv);
      }
      """;
}
