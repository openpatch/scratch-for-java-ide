package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowDocumentTest {

  @Test
  void createdWindowRoundTripsItsSettings() {
    var settings = new WindowDocument.Settings(640, 480, "Level1", true, true,
        "assets/images/logo.png", true);
    String source = WindowDocument.create("MyWindow", settings);
    assertThat(source).contains("super(640, 480);")
        .contains("this.setStage(new Level1());")
        .contains("this.setDebug(true);")
        .contains("Window.useFullScreen();")
        .contains("Window.useTextureSampling(TextureSampling.POINT);")
        .contains("import org.openpatch.scratch.TextureSampling;\n")
        .contains("Window.useSplashLogo(\"assets/images/logo.png\");");
    assertThat(WindowDocument.isManaged(source)).isTrue();
    assertThat(WindowDocument.read(source)).isEqualTo(settings);

    var plain = new WindowDocument.Settings(800, 600, "Level2", false, false, "", false);
    String rewritten = WindowDocument.write(source, plain);
    assertThat(rewritten).doesNotContain("useFullScreen").doesNotContain("setDebug")
        .contains("super(800, 600);").contains("new Level2()");
    assertThat(WindowDocument.read(rewritten)).isEqualTo(plain);
    assertThat(WindowDocument.write(rewritten, plain)).isEqualTo(rewritten);
  }

  @Test
  void handWrittenStatementsMakeItReadOnly() {
    String source = WindowDocument.create("MyWindow",
        new WindowDocument.Settings(480, 360, "MyStage", false, false, "", false))
        .replace("this.setStage(new MyStage());", "this.setStage(new MyStage());\n    foo();");
    assertThatThrownBy(() -> WindowDocument.read(source))
        .isInstanceOf(RegionStatements.UnsupportedRegionException.class);
  }
}
