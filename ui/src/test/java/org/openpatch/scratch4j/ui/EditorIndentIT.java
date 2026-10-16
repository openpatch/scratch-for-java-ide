package org.openpatch.scratch4j.ui;

import javafx.event.Event;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enter and closing brackets indent by the code's structure (needs a display
 * — {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorIndentIT {

  @TempDir
  Path tmp;

  private static void press(javafx.scene.Node node, KeyCode code, String typed) {
    Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
        false, false, false, false));
    if (typed != null) {
      Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_TYPED, typed, "", KeyCode.UNDEFINED,
          false, false, false, false));
    }
  }

  private static void type(javafx.scene.Node node, String text) {
    for (char c : text.toCharArray()) {
      press(node, KeyCode.UNDEFINED, String.valueOf(c));
    }
  }

  @Test
  void enterAndClosingBracesFollowTheBlocks() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      var apiIndex = org.openpatch.scratch4j.core.api.ApiIndex.load();
      CodeEditor code = new CodeEditor(tmp.resolve("Cat.java"),
          "public class Cat extends Sprite {\n  public void run() {", apiIndex, () -> tmp);
      var area = code.area();
      area.moveTo(area.getLength());

      press(area, KeyCode.ENTER, "\r");
      type(area, "if (x) {");
      press(area, KeyCode.ENTER, "\r");
      type(area, "move(1);");
      press(area, KeyCode.ENTER, "\r");
      type(area, "}");
      press(area, KeyCode.ENTER, "\r");
      type(area, "}");
      assertThat(area.getText()).isEqualTo("public class Cat extends Sprite {\n"
          + "  public void run() {\n    if (x) {\n      move(1);\n    }\n  }");

      // a line pasted at the left edge does not pull the next one there
      area.replaceText("class Cat {\n  void run() {\nmove(1);");
      area.moveTo(area.getLength());
      press(area, KeyCode.ENTER, "\r");
      type(area, "}");
      assertThat(area.getText()).isEqualTo("class Cat {\n  void run() {\nmove(1);\n  }");

      // Enter between the braces opens the block
      area.replaceText("class Cat {\n  void run() {}\n}");
      area.moveTo(area.getText().indexOf("{}") + 1);
      press(area, KeyCode.ENTER, "\r");
      assertThat(area.getText()).isEqualTo("class Cat {\n  void run() {\n    \n  }\n}");
      assertThat(area.getCurrentParagraph()).isEqualTo(2);
      assertThat(area.getCaretColumn()).isEqualTo(4);

      // a file indented by four spaces keeps its step
      area.replaceText("class Cat {\n    void run() {");
      area.moveTo(area.getLength());
      press(area, KeyCode.ENTER, "\r");
      assertThat(area.getText()).isEqualTo("class Cat {\n    void run() {\n        ");
    });
  }
}
