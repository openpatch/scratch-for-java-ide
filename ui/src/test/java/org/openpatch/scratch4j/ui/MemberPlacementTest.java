package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemberPlacementTest {

  private static final String STAGE = """
      public class MyStage extends Stage {
        public MyStage() {
          this.add(new Cat());
        }

        public void run() {
          // a { in a comment and "}" in a string are no braces
          String s = "}";
          if (s.isEmpty()) {
            this.wait(1);
          }
        }
      }
      """;

  private static int lineEnd(String text, String line) {
    int start = text.indexOf(line);
    return text.indexOf('\n', start);
  }

  @Test
  void aMethodBodyMovesTheBlockAfterTheMethod() {
    int inRun = lineEnd(STAGE, "this.wait(1);");
    var place = MemberPlacement.place(STAGE, inRun, "whenKeyPressed");
    assertThat(place).isNotNull();
    assertThat(place.existing()).isFalse();
    // the closing brace of run(), not of the if or the class
    assertThat(place.offset()).isEqualTo(STAGE.lastIndexOf("  }\n}") + 2);
  }

  @Test
  void theLineOpeningAMethodCountsAsInsideIt() {
    var place = MemberPlacement.place(STAGE, lineEnd(STAGE, "public MyStage() {"),
        "whenKeyPressed");
    assertThat(place.offset()).isEqualTo(STAGE.indexOf("  }\n\n") + 2);
  }

  @Test
  void theClassBodyStaysAsItIs() {
    assertThat(MemberPlacement.place(STAGE, lineEnd(STAGE, "    }\n\n") + 1, "whenKeyPressed"))
        .isNull();
  }

  @Test
  void outsideTheClassTheBlockGoesToItsEnd() {
    String text = "import org.openpatch.scratch.*;\n\n" + STAGE;
    var place = MemberPlacement.place(text, 0, "whenKeyPressed");
    // the end of the line before the class's closing brace
    assertThat(place.offset()).isEqualTo(text.lastIndexOf("\n}"));
  }

  @Test
  void anExistingEventMethodIsFoundInsteadOfASecondOne() {
    String text = STAGE.replace("  public void run() {",
        "  public void whenKeyPressed(KeyCode keyCode) {\n  }\n\n  public void run() {");
    var place = MemberPlacement.place(text, lineEnd(text, "this.wait(1);"), "whenKeyPressed");
    assertThat(place.existing()).isTrue();
    assertThat(place.offset()).isEqualTo(text.indexOf("keyCode) {") + "keyCode) {".length());
  }

  @Test
  void anAnonymousClassIsCodeOfTheMethod() {
    String text = """
        public class Cat extends Sprite {
          public void run() {
            Runnable r = new Runnable() {
              public void run() { }
            };
          }
        }
        """;
    var place = MemberPlacement.place(text, lineEnd(text, "public void run() { }"),
        "whenClicked");
    assertThat(place.offset()).isEqualTo(text.indexOf("  }\n}") + 2);
  }
}
