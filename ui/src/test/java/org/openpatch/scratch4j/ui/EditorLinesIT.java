package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Line editing (duplicate, delete, move) and the refactoring entries of the
 * editor's right-click menu (needs a display — {@code xvfb-run -a} — and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class EditorLinesIT {

  @TempDir
  Path tmp;

  @Test
  void lineOperationsAndRefactoringMenu() throws Exception {
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      var apiIndex = org.openpatch.scratch4j.core.api.ApiIndex.load();
      CodeEditor code = new CodeEditor(tmp.resolve("Player.java"), "a\nb\nc", apiIndex,
          () -> tmp);
      var area = code.area();

      area.moveTo(1, 0);
      code.duplicateLines();
      assertThat(area.getText()).isEqualTo("a\nb\nb\nc");
      assertThat(area.getCurrentParagraph()).isEqualTo(2);

      code.deleteLines();
      assertThat(area.getText()).isEqualTo("a\nb\nc");

      area.moveTo(2, 0);
      code.deleteLines(); // the last line takes the break before it
      assertThat(area.getText()).isEqualTo("a\nb");

      area.replaceText("a\nb\nc");
      area.moveTo(2, 1);
      code.moveLines(-1);
      assertThat(area.getText()).isEqualTo("a\nc\nb");
      assertThat(area.getCurrentParagraph()).isEqualTo(1);
      code.moveLines(-1);
      code.moveLines(-1); // already at the top: nothing happens
      assertThat(area.getText()).isEqualTo("c\na\nb");
      // two selected lines move together
      area.selectRange(0, 0, 1, 1);
      code.moveLines(1);
      assertThat(area.getText()).isEqualTo("b\nc\na");
      assertThat(area.getSelectedText()).isEqualTo("c\na");

      area.replaceText("int score = 0;\nscore++;");
      area.moveTo(6);
      assertThat(code.searchStart()).isEqualTo("score");
      List<Integer> renamed = new ArrayList<>();
      List<Integer> usages = new ArrayList<>();
      code.setOnRename(renamed::add);
      code.setOnFindUsages(usages::add);
      var items = code.contextMenuItems();
      items.stream().filter(i -> i.getText() != null && i.getText().contains("score"))
          .forEach(i -> i.fire());
      assertThat(renamed).containsExactly(4);
      assertThat(usages).containsExactly(4);

      // an event block dropped into a method lands after it, in the class body
      area.replaceText("public class Cat extends Sprite {\n  public void run() {\n"
          + "    this.move(1);\n  }\n}\n");
      area.moveTo(2, 4);
      code.insertBlock("public void whenClicked() {\n  \n}\n");
      assertThat(area.getText()).isEqualTo("public class Cat extends Sprite {\n"
          + "  public void run() {\n    this.move(1);\n  }\n\n"
          + "  public void whenClicked() {\n    \n  }\n}\n");
      // a second one takes the caret into the method that is already there
      area.moveTo(2, 4);
      String before = area.getText();
      code.insertBlock("public void whenClicked() {\n  \n}\n");
      assertThat(area.getText()).isEqualTo(before);
      assertThat(area.getCurrentParagraph()).isEqualTo(6);
    });
  }
}
