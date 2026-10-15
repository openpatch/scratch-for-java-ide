package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainWindowTest {

  /** The way the tutorials and demos start. */
  private static final String CATCH = """
      import org.openpatch.scratch.*;

      public class CatchStage extends Stage {

        public CatchStage() {
          this.addBackdrop("background");
        }

        public static void main(String[] args) {
          Window myWindow = new Window(600, 400);
          myWindow.setStage(new CatchStage());
        }
      }
      """;

  @Test
  void readsTheWindowMadeInMain() {
    assertThat(MainWindow.has(CATCH)).isTrue();
    assertThat(MainWindow.read(CATCH)).isEqualTo(
        new WindowDocument.Settings(600, 400, "CatchStage", false, false, "", false));
  }

  @Test
  void writesTheSettingsWhereTheWindowIsMade() {
    var all = new WindowDocument.Settings(800, 600, "CatchStage", true, true,
        "assets/images/logo.png", true);
    String written = MainWindow.write(CATCH, all);
    assertThat(written).contains("""
            Window.useFullScreen();
            Window.useTextureSampling(TextureSampling.POINT);
            Window.useSplashLogo("assets/images/logo.png");
            Window myWindow = new Window(800, 600);
            myWindow.setStage(new CatchStage());
            myWindow.setDebug(true);
          }
        """);
    // the wildcard import covers TextureSampling; the constructor is untouched
    assertThat(written).doesNotContain("import org.openpatch.scratch.TextureSampling;")
        .contains("this.addBackdrop(\"background\");");
    assertThat(MainWindow.read(written)).isEqualTo(all);
    assertThat(MainWindow.write(written, all)).as("nothing to change").isEqualTo(written);

    // and back: every option line goes again
    var plain = new WindowDocument.Settings(600, 400, "CatchStage", false, false, "", false);
    assertThat(MainWindow.write(written, plain)).isEqualTo(CATCH);
    assertThat(MainWindow.write(CATCH, plain)).isSameAs(CATCH);
  }

  @Test
  void keepsTheAssetsFolderAndReadsVarAndCompactMains() {
    String compact = """
        void main() {
          var window = new Window(1200, 800, "assets");
          window.setDebug(true);
          window.setStage(new Level());
        }
        """;
    assertThat(MainWindow.read(compact)).isEqualTo(
        new WindowDocument.Settings(1200, 800, "Level", false, false, "", true));
    String smaller = MainWindow.write(compact,
        new WindowDocument.Settings(640, 480, "Level", false, true, "", false));
    assertThat(smaller).contains("var window = new Window(640, 480, \"assets\");")
        .doesNotContain("setDebug")
        .startsWith("import org.openpatch.scratch.TextureSampling;\n")
        .contains("  Window.useTextureSampling(TextureSampling.POINT);\n"
            + "  var window = new Window(640, 480, \"assets\");");
  }

  @Test
  void aWindowWithoutANameGetsTheDebugOverlayThroughItsInstance() {
    String source = """
        public class Main {
          public static void main(String[] args) {
            new Window(480, 360).setStage(new MyStage()); // the start
          }
        }
        """;
    String written = MainWindow.write(source,
        new WindowDocument.Settings(480, 360, null, false, false, "", true));
    assertThat(written).contains("""
            new Window(480, 360).setStage(new MyStage()); // the start
            Window.getInstance().setDebug(true);
        """);
    assertThat(MainWindow.read(written).debug()).isTrue();
  }

  @Test
  void aSizeThatIsNotTwoNumbersIsReadOnly() {
    String source = CATCH.replace("new Window(600, 400)", "new Window(WIDTH, HEIGHT)");
    assertThatThrownBy(() -> MainWindow.read(source))
        .isInstanceOf(RegionStatements.UnsupportedRegionException.class);
  }

  @Test
  void onlyAWindowMadeInMainCounts() {
    assertThat(MainWindow.has("""
        public class MyStage extends Stage {
          public void whenKeyPressed(int key) {
            new Window(480, 360);
          }
        }
        """)).isFalse();
    assertThat(MainWindow.has("""
        public class Main {
          public static void main(String[] args) {
            new MyWindow();
          }
        }
        """)).isFalse();
  }
}
