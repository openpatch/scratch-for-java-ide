package org.openpatch.scratch4j.ui;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.LibraryJarSource;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Moving boxes in the class diagram undoes and redoes, and the kept arrangement follows. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class DiagramUndoIT {

  @TempDir
  Path tmp;

  private static void press(Node target, KeyCode code, boolean shift) {
    boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
    Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
        shift, !mac, false, mac));
  }

  @Test
  void movingAClassUndoesAndRedoes() throws Exception {
    Path tiled = BundledTemplates.create("demo-tiled", tmp, "tiled",
        LibraryJarSource.allJar(Path.of(System.getProperty("user.dir"), "target", "bundled")));
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      DiagramView diagrams = new DiagramView(ScratchProject.open(tiled), () -> null, f -> { });
      Scene scene = new Scene(diagrams, 1300, 800);
      Theme.apply(scene);
      diagrams.applyCss();
      diagrams.layout();
      UmlCanvas classes = diagrams.canvas();
      Button undo = (Button) diagrams.lookupAll(".button").stream()
          .filter(b -> b instanceof Button button && button.getTooltip() != null
              && button.getTooltip().getText().equals(I18n.t("designer.undo")))
          .findFirst().orElseThrow();
      assertThat(undo.isDisabled()).isTrue();

      double[] player = classes.placed().get("Player").clone();
      LayoutIT.drag(classes, player[0] + 20, player[1] + 10, player[0] + 170, player[1] + 50);
      assertThat(classes.placed().get("Player")[0]).isEqualTo(player[0] + 150);
      assertThat(tiled.resolve(".scratch4j/diagram.json")).exists();
      assertThat(undo.isDisabled()).isFalse();

      // Ctrl+Z: the diagram arranges itself again, as before the move
      press(classes, KeyCode.Z, false);
      assertThat(classes.placed().get("Player")).startsWith(player[0], player[1]);
      assertThat(classes.arrangedByHand()).isFalse();
      assertThat(tiled.resolve(".scratch4j/diagram.json")).doesNotExist();
      assertThat(undo.isDisabled()).isTrue();

      // Ctrl+Y (or Ctrl+Shift+Z) moves it there again, and it is kept
      press(classes, KeyCode.Y, false);
      assertThat(classes.placed().get("Player")[0]).isEqualTo(player[0] + 150);
      assertThat(tiled.resolve(".scratch4j/diagram.json")).exists();

      // arranging automatically undoes too
      classes.arrangeAutomatically();
      assertThat(classes.placed().get("Player")).startsWith(player[0], player[1]);
      press(classes, KeyCode.Z, false);
      assertThat(classes.placed().get("Player")[0]).isEqualTo(player[0] + 150);
      press(classes, KeyCode.Z, true);
      assertThat(classes.placed().get("Player")).startsWith(player[0], player[1]);
    });
  }
}
