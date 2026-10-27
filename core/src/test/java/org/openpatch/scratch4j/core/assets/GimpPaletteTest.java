package org.openpatch.scratch4j.core.assets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GimpPaletteTest {

  @TempDir
  Path dir;

  @Test
  void readsNameColumnsCommentsAndColours() {
    GimpPalette palette = GimpPalette.parse("""
        GIMP Palette
        Name: PICO-8
        Columns: 8
        # from lospec.com
          0   0   0\tblack
         29  43  83\tdark blue
        255 241 232
        """);
    assertThat(palette.name()).isEqualTo("PICO-8");
    assertThat(palette.columns()).isEqualTo(8);
    assertThat(palette.entries()).containsExactly(
        new GimpPalette.Entry(0, 0, 0, "black"),
        new GimpPalette.Entry(29, 43, 83, "dark blue"),
        new GimpPalette.Entry(255, 241, 232, ""));
    assertThat(palette.entries().get(1).hex()).isEqualTo("#1d2b53");
    assertThat(palette.entries().get(1).rgb()).isEqualTo(0x1d2b53);
  }

  @Test
  void isLenientAboutWhitespaceLineEndingsAndBrokenLines() {
    GimpPalette palette = GimpPalette.parse(
        "﻿GIMP Palette\r\nname:  Odd  \r\n1\t2   3 a\r\nnot a colour\r\n4 5\r\n"
            + "-1 0 0 negative\r\n300 0 7 clamped\r\nColumns: lots\r\n");
    assertThat(palette.name()).isEqualTo("Odd");
    assertThat(palette.columns()).isZero();
    assertThat(palette.entries()).containsExactly(
        new GimpPalette.Entry(1, 2, 3, "a"),
        new GimpPalette.Entry(255, 0, 7, "clamped"));
  }

  @Test
  void readsPastTheAlphaColumnOfRgbaPalettes() {
    GimpPalette palette = GimpPalette.parse("""
        GIMP Palette
        Channels: RGBA
        10 20 30 128 half
        40 50 60 255
        70 80 90
        """);
    assertThat(palette.entries()).containsExactly(
        new GimpPalette.Entry(10, 20, 30, "half"),
        new GimpPalette.Entry(40, 50, 60, ""),
        new GimpPalette.Entry(70, 80, 90, ""));
  }

  @Test
  void takesTheNameFromLospecsCommentWhenThereIsNoNameLine() {
    GimpPalette palette = GimpPalette.parse("""
        GIMP Palette
        #Palette Name: PICO-8
        #Colors: 16
        0	0	0	000000
        """);
    assertThat(palette.name()).isEqualTo("PICO-8");
    assertThat(palette.entries()).containsExactly(new GimpPalette.Entry(0, 0, 0, "000000"));
    assertThat(GimpPalette.parse("GIMP Palette\nName: Own\n#Palette Name: Other\n").name())
        .isEqualTo("Own");
  }

  @Test
  void collectsAnImagesColoursMostFrequentFirstWithoutTheBackground() {
    var image = new java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0xff0000ff);
    image.setRGB(1, 0, 0xff00ff00);
    image.setRGB(2, 0, 0x8000ff00); // half opaque: still paint
    image.setRGB(0, 1, 0x7fff0000); // mostly transparent: background
    assertThat(GimpPalette.colorsOf(image)).containsExactly(
        new GimpPalette.Entry(0, 255, 0, ""),
        new GimpPalette.Entry(0, 0, 255, ""));
  }

  @Test
  void rejectsTextWithoutTheHeader() {
    assertThatThrownBy(() -> GimpPalette.parse("0 0 0 black"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GIMP Palette");
    assertThatThrownBy(() -> GimpPalette.parse("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void writesTheCanonicalFormAndReadsItBack() throws IOException {
    GimpPalette palette = new GimpPalette("Mine", 4, List.of(
        new GimpPalette.Entry(0, 0, 0, "black"),
        new GimpPalette.Entry(255, 128, 7, "")));
    assertThat(palette.format()).isEqualTo("""
        GIMP Palette
        Name: Mine
        Columns: 4
        #
          0   0   0\tblack
        255 128   7
        """);
    Path file = dir.resolve("assets/palettes/mine.gpl");
    palette.write(file);
    assertThat(GimpPalette.read(file)).isEqualTo(palette);
    assertThat(GimpPalette.parse(new GimpPalette("", 0, List.of()).format()))
        .isEqualTo(new GimpPalette("", 0, List.of()));
  }

  @Test
  void reportsTheFileOfAnUnreadablePalette() throws IOException {
    Path file = dir.resolve("broken.gpl");
    Files.writeString(file, "JASC-PAL\n0100\n");
    assertThatThrownBy(() -> GimpPalette.read(file))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("broken.gpl");
  }
}
