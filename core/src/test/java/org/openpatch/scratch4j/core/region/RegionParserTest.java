package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionParserTest {

  // A representative stage with managed regions.
  private static final String SAMPLE = """
      public class MyStage extends Stage {
        // scratch4j:begin fields  (managed by the stage designer)
        Player player;
        // scratch4j:end fields

        public MyStage() {
          super(480, 360);
          // scratch4j:begin setup  (managed by the stage designer)
          this.addBackdrop("background");
          player = new Player();
          player.setPosition(-120, 40);
          player.setDirection(90);
          player.setSize(80);
          this.add(player);
          // scratch4j:end setup
        }
      }
      """;

  @Test
  void findsRegionsWithTrailingMarkerProse() {
    List<Region> regions = RegionParser.parse(SAMPLE);
    assertThat(regions).extracting(Region::id).containsExactly("fields", "setup");
    assertThat(RegionParser.find(SAMPLE, "fields").body())
        .isEqualTo("  Player player;\n");
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
    assertThat(rewritten).contains("Enemy enemy;\n  // scratch4j:end fields");
  }

  @Test
  void emptyRegionsRoundTrip() {
    String empty = """
        public class Player extends Sprite {
          public Player() {
            // scratch4j:begin setup (managed by the stage designer)
            // scratch4j:end setup
          }
        }
        """;
    Region region = RegionParser.find(empty, "setup");
    assertThat(region.body()).isEmpty();
    assertThat(RegionParser.rewrite(empty, "setup", region.body())).isEqualTo(empty);
    assertThat(RegionParser.rewrite(empty, "setup", "    this.setSize(50);\n"))
        .contains("this.setSize(50);\n    // scratch4j:end setup");
  }

  @Test
  void rejectsMissingEndMarker() {
    assertThatThrownBy(() -> RegionParser.parse("""
        // scratch4j:begin fields
        int x;
        """))
        .isInstanceOf(RegionParser.RegionParseException.class)
        .hasMessageContaining("fields");
  }

  @Test
  void rejectsMismatchedEndMarker() {
    assertThatThrownBy(() -> RegionParser.parse("""
        // scratch4j:begin fields
        // scratch4j:end setup
        """))
        .isInstanceOf(RegionParser.RegionParseException.class)
        .hasMessageContaining("fields");
  }

  @Test
  void rejectsDuplicateIds() {
    assertThatThrownBy(() -> RegionParser.parse("""
        // scratch4j:begin a
        // scratch4j:end a
        // scratch4j:begin a
        // scratch4j:end a
        """))
        .isInstanceOf(RegionParser.RegionParseException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void rejectsMarkersInsideANewBody() {
    assertThatThrownBy(() -> RegionParser.rewrite(SAMPLE, "setup",
        "// scratch4j:begin other\n"))
        .isInstanceOf(RegionParser.RegionParseException.class);
  }

  @Test
  void fileWithoutMarkersHasNoRegions() {
    assertThat(RegionParser.parse("class A { }")).isEmpty();
    assertThat(RegionParser.has("class A { }", "setup")).isFalse();
  }
}
