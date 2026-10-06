package org.openpatch.scratch4j.core.assets;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
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
      crop = rotateClockwise(crop, 90 - entry.direction());
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

  private static BufferedImage rotateClockwise(BufferedImage image, double degrees) {
    double rad = Math.toRadians(degrees);
    double w = image.getWidth();
    double h = image.getHeight();
    double sin = Math.abs(Math.sin(rad));
    double cos = Math.abs(Math.cos(rad));
    int newW = (int) Math.floor(w * cos + h * sin);
    int newH = (int) Math.floor(w * sin + h * cos);
    BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
    AffineTransform transform = new AffineTransform();
    transform.translate(newW / 2.0, newH / 2.0);
    transform.rotate(rad);
    transform.translate(-w / 2.0, -h / 2.0);
    Graphics2D g = out.createGraphics();
    g.drawImage(image, new AffineTransformOp(transform,
        AffineTransformOp.TYPE_NEAREST_NEIGHBOR), 0, 0);
    g.dispose();
    return out;
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
