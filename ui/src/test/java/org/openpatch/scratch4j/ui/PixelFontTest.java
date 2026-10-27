package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PixelFontTest {

  @Test
  void hasEveryPrintableAsciiCharacterAndTheGermanLetters() {
    var glyphs = PixelFont.glyphs();
    for (char c = ' '; c <= '~'; c++) {
      assertThat(glyphs).as("glyph for '%s'", c).containsKey(c);
    }
    assertThat(glyphs).containsKeys('Ä', 'Ö', 'Ü', 'ä', 'ö', 'ü', 'ß');
  }

  @Test
  void glyphsAreAtMostFiveDotsWideAndFitTheLine() {
    PixelFont.glyphs().forEach((c, glyph) -> {
      assertThat(glyph.width()).as("width of '%s'", c).isBetween(1, 5);
      // nothing below the descender rows; capitals and digits sit on the baseline
      boolean[][] dots = glyph.dots();
      assertThat(dots.length).isEqualTo(PixelFont.HEIGHT);
      if (Character.isUpperCase(c) && c < 128 || Character.isDigit(c)) {
        boolean onBaseline = false;
        for (boolean dot : dots[PixelFont.ASCENT - 1]) onBaseline |= dot;
        assertThat(onBaseline).as("'%s' on the baseline", c).isTrue();
        for (boolean dot : dots[PixelFont.ASCENT]) assertThat(dot).isFalse();
      }
    });
  }

  @Test
  void measuresTextWithOneDotBetweenCharacters() {
    // "Hi": H is 5 wide, i 3, one gap
    assertThat(PixelFont.width("Hi", 1)).isEqualTo(9);
    assertThat(PixelFont.width("Hi", 2)).isEqualTo(18);
    // a character the font lacks draws as "?"
    assertThat(PixelFont.glyph('€')).isSameAs(PixelFont.glyph('?'));
  }
}
