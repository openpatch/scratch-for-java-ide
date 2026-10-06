package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignerPromotionTest {

  private static final String STAGE = """
      import org.openpatch.scratch.*;

      public class Quiz extends Stage {
        // scratch4j:begin fields (managed by the stage designer)
        // scratch4j:end fields

        private int right = 0;

        public Quiz() {
          super(600, 360);
          // scratch4j:begin setup (managed by the stage designer)
          this.addBackdrop("background");
          // scratch4j:end setup

          var host = new Sprite();
          host.addCostume("alienGreen_stand");
          host.setY(-20);
          this.add(host);
          this.add(new Basket());
          for (int i = 0; i < 3; i++) {
            this.add(new Coin());
          }
        }
      }
      """;

  @Test
  void spritesAfterTheRegionMoveIntoItInOrder() {
    var found = DesignerPromotion.find(STAGE);
    assertThat(found).extracting(DesignerPromotion.Promotion::name).containsExactly("host");
    String promoted = DesignerPromotion.apply(STAGE, found.get(0).firstLine());
    assertThat(promoted).contains("  private Sprite host;\n  // scratch4j:end fields")
        .contains("    this.addBackdrop(\"background\");\n"
            + "    host = new Sprite();\n"
            + "    host.setPosition(0, -20);\n"
            + "    host.addCostume(\"alienGreen_stand\");\n"
            + "    this.add(host);\n"
            + "    // scratch4j:end setup")
        .doesNotContain("var host");
    var model = StageDocument.read(promoted).model();
    assertThat(model.sprites().byName("host").y()).isEqualTo(-20);
    // now the basket is next to the region: it can follow
    var next = DesignerPromotion.find(promoted);
    assertThat(next).extracting(DesignerPromotion.Promotion::name).containsExactly("basket");
    String both = DesignerPromotion.apply(promoted, next.get(0).firstLine());
    assertThat(StageDocument.read(both).model().sprites()).hasSize(2);
    assertThat(both).contains("for (int i = 0; i < 3; i++) {");
  }

  @Test
  void theDrawingOrderStays() {
    assertThatThrownBy(() -> DesignerPromotion.apply(STAGE,
        STAGE.lines().toList().indexOf("    this.add(new Basket());") + 1))
        .hasMessageContaining("drawing order");
  }
}
