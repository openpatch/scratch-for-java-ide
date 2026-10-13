package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionParserTest {

  // A representative stage with managed regions.
  private static final String SAMPLE = """
      public class MyStage extends Stage {
        // begin stage designer
        Player player;
        // end stage designer

        public MyStage() {
          // begin stage designer
          this.addBackdrop("background");
          player = new Player();
          player.setPosition(-120, 40);
          player.setDirection(90);
          player.setSize(80);
          this.add(player);
          // end stage designer
        }
      }
      """;

  @Test
  void aBlockIsNamedByWhereItStands() {
    List<Region> regions = RegionParser.parse(SAMPLE);
    assertThat(regions).extracting(Region::id).containsExactly("fields", "setup");
    assertThat(regions).extracting(Region::editor)
        .containsOnly(RegionParser.STAGE_DESIGNER);
    assertThat(RegionParser.find(SAMPLE, "fields").body())
        .isEqualTo("  Player player;\n");
  }

  @Test
  void theProjectSettingsHaveABlockInTheWindowAndOneInMain() {
    String window = """
        public class MyWindow extends Window {
          public MyWindow() {
            super(480, 360);
            // begin project settings
            this.setStage(new MyStage());
            // end project settings
          }

          public static void main(String[] args) {
            String s = "{ not a brace }";
            // begin project settings
            Window.useFullScreen();
            // end project settings
            new MyWindow();
          }
        }
        """;
    assertThat(RegionParser.parse(window)).extracting(Region::id)
        .containsExactly("window", "options");
    assertThat(RegionParser.find(window, "options").body()).contains("useFullScreen");
  }

  @Test
  void rewriteWithSameBodyIsLossless() {
    for (Region region : RegionParser.parse(SAMPLE)) {
      String rewritten = RegionParser.rewrite(SAMPLE, region.id(), region.body());
      assertThat(rewritten).isEqualTo(SAMPLE);
    }
  }

  @Test
  void rewriteChangesOnlyTheRegionBody() {
    String rewritten = RegionParser.rewrite(SAMPLE, "setup", "    this.add(new Enemy());\n");
    assertThat(rewritten)
        .doesNotContain("this.addBackdrop(\"background\");")
        .doesNotContain("player.setDirection(90);")
        .contains("this.add(new Enemy());")
        .contains("Player player;\n") // fields region untouched
        .startsWith("public class MyStage extends Stage {\n");
  }

  @Test
  void rewriteAppendsMissingTrailingNewline() {
    String rewritten = RegionParser.rewrite(SAMPLE, "fields", "Enemy enemy;");
    assertThat(rewritten).contains("Enemy enemy;\n  // end stage designer");
  }

  @Test
  void emptyRegionsRoundTrip() {
    String empty = """
        public class Player extends Sprite {
          public Player() {
            // begin sprite editor
            // end sprite editor
          }
        }
        """;
    Region region = RegionParser.find(empty, "setup");
    assertThat(region.body()).isEmpty();
    assertThat(region.editor()).isEqualTo(RegionParser.SPRITE_EDITOR);
    assertThat(RegionParser.rewrite(empty, "setup", region.body())).isEqualTo(empty);
    assertThat(RegionParser.rewrite(empty, "setup", "    this.setSize(50);\n"))
        .contains("this.setSize(50);\n    // end sprite editor");
  }

  @Test
  void windowsLineEndingsParse() {
    String crlf = SAMPLE.replace("\n", "\r\n");
    assertThat(RegionParser.parse(crlf)).extracting(Region::id).containsExactly("fields", "setup");
    assertThat(RegionParser.rewrite(crlf, "setup", RegionParser.find(crlf, "setup").body()))
        .isEqualTo(crlf);
  }

  @Test
  void rejectsMissingEndMarker() {
    assertThatThrownBy(() -> RegionParser.parse("""
        // begin stage designer
        int x;
        """))
        .isInstanceOf(RegionParser.RegionParseException.class)
        .hasMessageContaining("// end stage designer");
  }

  @Test
  void rejectsMismatchedEndMarker() {
    assertThatThrownBy(() -> RegionParser.parse("""
        // begin stage designer
        // end sprite editor
        """))
        .isInstanceOf(RegionParser.RegionParseException.class)
        .hasMessageContaining("sprite editor");
  }

  @Test
  void rejectsTwoBlocksInTheSamePlace() {
    assertThatThrownBy(() -> RegionParser.parse("""
        class A extends Sprite {
          A() {
            // begin sprite editor
            // end sprite editor
            // begin sprite editor
            // end sprite editor
          }
        }
        """))
        .isInstanceOf(RegionParser.RegionParseException.class)
        .hasMessageContaining("second");
  }

  @Test
  void rejectsMarkersInsideANewBody() {
    assertThatThrownBy(() -> RegionParser.rewrite(SAMPLE, "setup",
        "// begin sprite editor\n"))
        .isInstanceOf(RegionParser.RegionParseException.class);
  }

  @Test
  void otherCommentsAreNoMarkers() {
    String plain = "class A {\n  // begin the game\n  // end of story\n}\n";
    assertThat(RegionParser.parse(plain)).isEmpty();
  }

  @Test
  void fileWithoutMarkersHasNoRegions() {
    assertThat(RegionParser.parse("class A { }")).isEmpty();
    assertThat(RegionParser.has("class A { }", "setup")).isFalse();
  }
}
