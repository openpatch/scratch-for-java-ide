package org.openpatch.scratch4j.ui;

import org.openpatch.scratch4j.core.io.AtomicFiles;
import org.openpatch.scratch4j.sound.SoundClip;
import org.openpatch.scratch4j.sound.SoundIO;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates editable project assets without depending on the JavaFX toolkit. */
final class ProjectAssetCreator {

  enum Kind { IMAGE, SOUND, FRAGMENT_SHADER, VERTEX_SHADER }

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
      case FRAGMENT_SHADER, VERTEX_SHADER -> "assets/shaders";
    };
    String extension = switch (kind) {
      case IMAGE -> ".png";
      case SOUND -> ".wav";
      case FRAGMENT_SHADER -> ".frag";
      case VERTEX_SHADER -> ".vert";
    };
    Path dir = root.resolve(folder);
    Path target = dir.resolve(name + extension);
    if (Files.exists(target)) {
      throw new IOException("Asset already exists: " + target.getFileName());
    }
    Files.createDirectories(dir);
    if (kind == Kind.FRAGMENT_SHADER || kind == Kind.VERTEX_SHADER) {
      AtomicFiles.writeString(target, kind == Kind.FRAGMENT_SHADER
          ? FRAGMENT_TEMPLATE : VERTEX_TEMPLATE);
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
      #ifdef GL_ES
      precision mediump float;
      #endif

      #define PROCESSING_TEXTURE_SHADER

      uniform sampler2D texture;
      varying vec4 vertTexCoord;

      void main(void) {
        gl_FragColor = texture2D(texture, vertTexCoord.st);
      }
      """;

  private static final String VERTEX_TEMPLATE = """
      uniform mat4 transformMatrix;
      uniform mat4 texMatrix;

      attribute vec4 position;
      attribute vec4 color;
      attribute vec2 texCoord;

      varying vec4 vertColor;
      varying vec4 vertTexCoord;

      void main(void) {
        gl_Position = transformMatrix * position;
        vertColor = color;
        vertTexCoord = texMatrix * vec4(texCoord, 1.0, 1.0);
      }
      """;
}
