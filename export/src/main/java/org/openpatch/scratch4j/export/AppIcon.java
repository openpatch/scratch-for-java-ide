package org.openpatch.scratch4j.export;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * App icons from any project image (a costume, the splash logo): squared,
 * scaled, and written as PNG (Linux), ICNS (macOS bundle) and ICO (Windows).
 * Both container formats can hold PNG data directly, so no extra libraries.
 */
public final class AppIcon {

  private AppIcon() {}

  /** The image centred on a transparent square of {@code size} pixels. */
  static BufferedImage square(BufferedImage source, int size) {
    BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    var g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    double scale = Math.min((double) size / source.getWidth(), (double) size / source.getHeight());
    int w = (int) Math.round(source.getWidth() * scale);
    int h = (int) Math.round(source.getHeight() * scale);
    g.drawImage(source, (size - w) / 2, (size - h) / 2, w, h, null);
    g.dispose();
    return out;
  }

  private static byte[] png(BufferedImage image) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private static BufferedImage read(Path image) throws IOException {
    BufferedImage source = ImageIO.read(image.toFile());
    if (source == null) {
      throw new IOException("Not an image: " + image.getFileName());
    }
    return source;
  }

  public static void writePng(Path image, int size, Path target) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Files.write(target, png(square(read(image), size)));
  }

  /** macOS: 'icns' with PNG entries ic07 (128), ic08 (256), ic09 (512). */
  public static void writeIcns(Path image, Path target) throws IOException {
    BufferedImage source = read(image);
    ByteArrayOutputStream entries = new ByteArrayOutputStream();
    DataOutputStream data = new DataOutputStream(entries);
    String[] types = {"ic07", "ic08", "ic09"};
    int[] sizes = {128, 256, 512};
    for (int i = 0; i < types.length; i++) {
      byte[] png = png(square(source, sizes[i]));
      data.writeBytes(types[i]);
      data.writeInt(png.length + 8);
      data.write(png);
    }
    ByteArrayOutputStream file = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(file);
    out.writeBytes("icns");
    out.writeInt(entries.size() + 8);
    out.write(entries.toByteArray());
    Files.createDirectories(target.toAbsolutePath().getParent());
    Files.write(target, file.toByteArray());
  }

  /** Windows: an ICO holding one 256x256 PNG (Vista and later). */
  public static void writeIco(Path image, Path target) throws IOException {
    byte[] png = png(square(read(image), 256));
    ByteBuffer header = ByteBuffer.allocate(6 + 16).order(ByteOrder.LITTLE_ENDIAN);
    header.putShort((short) 0).putShort((short) 1).putShort((short) 1);
    header.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0); // 256 x 256
    header.putShort((short) 1).putShort((short) 32);
    header.putInt(png.length).putInt(6 + 16);
    Files.createDirectories(target.toAbsolutePath().getParent());
    try (var out = Files.newOutputStream(target)) {
      out.write(header.array());
      out.write(png);
    }
  }
}
