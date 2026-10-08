package org.openpatch.scratch4j.core.assets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * "Copy built-in costume/sound into the project to edit": crops a built-in
 * image out of its Kenney atlas (pre-rotated to face right, as the library's
 * loader does) into {@code assets/images}, and copies a built-in sound file
 * into {@code assets/sounds}. The project then owns an editable file.
 */
public final class AssetCopier {

  private AssetCopier() {}

  /** Copies the built-in image into assets/images/&lt;name&gt;.png; returns the file. */
  public static Path copyBuiltinImage(BuiltinImage entry, Path projectRoot)
      throws IOException {
    BufferedImage sheet;
    try (InputStream in = AssetCopier.class.getResourceAsStream("/" + entry.sheetPath())) {
      if (in == null) {
        throw new IOException("Sheet not found: " + entry.sheetPath());
      }
      sheet = ImageIO.read(in);
    }
    BufferedImage crop = sheet.getSubimage(entry.x(), entry.y(),
        entry.width(), entry.height());
    if (entry.direction() != 90) {
      // face right like the library does: a quarter turn, or mirrored when drawn facing left
      int[] argb = crop.getRGB(0, 0, crop.getWidth(), crop.getHeight(), null, 0,
          crop.getWidth());
      FacingRight.Pixels turned = FacingRight.turn(
          new FacingRight.Pixels(crop.getWidth(), crop.getHeight(), argb), entry.direction());
      crop = new BufferedImage(turned.width(), turned.height(), BufferedImage.TYPE_INT_ARGB);
      crop.setRGB(0, 0, turned.width(), turned.height(), turned.argb(), 0, turned.width());
    }
    Path target = uniqueTarget(projectRoot.resolve("assets/images"),
        entry.name() + ".png");
    ImageIO.write(crop, "png", target.toFile());
    return target;
  }

  /** Copies the built-in sound into assets/sounds/&lt;name&gt;.&lt;ext&gt;; returns the file. */
  public static Path copyBuiltinSound(String soundName, Path projectRoot)
      throws IOException {
    String resource = org.openpatch.scratch.internal.BuiltinSounds.get(soundName);
    if (resource == null) {
      throw new IOException("Not a built-in sound: " + soundName);
    }
    String ext = resource.substring(resource.lastIndexOf('.') + 1);
    Path target = uniqueTarget(projectRoot.resolve("assets/sounds"),
        soundName + "." + ext);
    try (InputStream in = AssetCopier.class.getResourceAsStream("/" + resource)) {
      if (in == null) {
        throw new IOException("Sound not found in the jar: " + resource);
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  private static Path uniqueTarget(Path dir, String fileName) throws IOException {
    Files.createDirectories(dir);
    Path target = dir.resolve(fileName);
    String base = fileName.substring(0, fileName.lastIndexOf('.'));
    String ext = fileName.substring(fileName.lastIndexOf('.'));
    int n = 2;
    while (Files.exists(target)) {
      target = dir.resolve(base + n++ + ext);
    }
    return target;
  }
}
