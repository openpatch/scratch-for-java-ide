package org.openpatch.scratch4j.core.assets;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turns SVG drawings into PNG costumes (the library loads PNG, JPG and GIF
 * only): Scratch 3's vector costumes on import, and SVG files in the paint
 * editor. Rendered with Batik at a scale (2 keeps Scratch's own crispness).
 */
public final class SvgRasterizer {

  private SvgRasterizer() {}

  /** Renders {@code svg} at {@code scale} x its own size into {@code png}. */
  public static void toPng(byte[] svg, double scale, Path png) throws IOException {
    int[] size = size(svg);
    PNGTranscoder transcoder = new PNGTranscoder();
    transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) Math.max(1, size[0] * scale));
    transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) Math.max(1, size[1] * scale));
    // Scratch drawings use no external resources; never fetch any
    transcoder.addTranscodingHint(PNGTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, Boolean.FALSE);
    Files.createDirectories(png.toAbsolutePath().getParent());
    try (OutputStream out = Files.newOutputStream(png)) {
      transcoder.transcode(new TranscoderInput(new ByteArrayInputStream(svg)),
          new TranscoderOutput(out));
    } catch (TranscoderException | RuntimeException e) {
      Files.deleteIfExists(png);
      throw new IOException("Cannot draw the SVG: " + e.getMessage(), e);
    }
  }

  /** width/height of an SVG (attributes, else viewBox), 100x100 when unknown. */
  public static int[] size(byte[] svg) {
    String text = new String(svg, java.nio.charset.StandardCharsets.UTF_8);
    java.util.regex.Matcher root = java.util.regex.Pattern.compile("<svg[^>]*>",
        java.util.regex.Pattern.DOTALL).matcher(text);
    if (!root.find()) {
      return new int[] {100, 100};
    }
    String tag = root.group();
    double width = -1;
    double height = -1;
    java.util.regex.Matcher w = java.util.regex.Pattern.compile("\\bwidth=\"([0-9.]+)").matcher(tag);
    java.util.regex.Matcher h = java.util.regex.Pattern.compile("\\bheight=\"([0-9.]+)").matcher(tag);
    if (w.find()) width = Double.parseDouble(w.group(1));
    if (h.find()) height = Double.parseDouble(h.group(1));
    java.util.regex.Matcher box = java.util.regex.Pattern.compile(
        "viewBox=\"[-0-9.]+[ ,]+[-0-9.]+[ ,]+([0-9.]+)[ ,]+([0-9.]+)").matcher(tag);
    if (box.find()) {
      if (width < 0) width = Double.parseDouble(box.group(1));
      if (height < 0) height = Double.parseDouble(box.group(2));
    }
    return new int[] {(int) Math.max(1, Math.round(width < 0 ? 100 : width)),
        (int) Math.max(1, Math.round(height < 0 ? 100 : height))};
  }
}
