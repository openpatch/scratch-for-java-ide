package org.openpatch.scratch4j.core.region;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShaderUsesTest {

  @Test
  void addsAndTheirLiteralUniforms() {
    String source = String.join("\n",
        "public class MySprite extends Sprite {",
        "  public MySprite() {",
        "    var shader = this.getShaders().add(\"halftone\", \"shaders/halftone.frag\", \"shaders/default.vert\");",
        "    shader = this.getShaders().add(\"pixelate\", \"shaders/pixelate.frag\", null);",
        "    shader.set(\"pixels\", 20.0, 10.0);",
        "    getShaders().add(\"neon\", \"shaders/neon.frag\");",
        "    this.getShaders().get(\"neon\").set(\"brt\", 0.4f);",
        "    this.getShaders().get(\"neon\").set(\"rad\", (float) 1);",
        "  }",
        "  public void run() {",
        "    shader.set(\"pixels\", this.getX(), 3);",
        "    this.getShaders().get(\"halftone\").set(\"pixelsPerRow\", 30);",
        "  }",
        "}");
    var uses = ShaderUses.in(source);
    assertThat(uses).extracting(ShaderUses.Use::name)
        .containsExactly("halftone", "pixelate", "neon");
    assertThat(uses.get(0).line()).isEqualTo(2);
    assertThat(uses.get(0).vert()).isEqualTo("shaders/default.vert");
    assertThat(uses.get(1).vert()).isNull();
    assertThat(uses.get(1).uniforms().get("pixels")).containsExactly(20.0, 10.0);
    assertThat(uses.get(2).vert()).isNull();
    assertThat(uses.get(2).uniforms().get("brt")).containsExactly(0.4);
    assertThat(uses.get(2).uniforms().get("rad")).containsExactly(1.0);
    // a literal in run() counts too (the first value only), a computed one never
    assertThat(uses.get(0).uniforms().get("pixelsPerRow")).containsExactly(30.0);
    assertThat(ShaderUses.of(source, "shaders/default.vert")).hasSize(1);
  }
}
