package org.openpatch.scratch4j.ui;

import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Abiturklassen dialog (needs a display: {@code xvfb-run -a} and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class AbiturklassenDialogIT {

  @Test
  @SuppressWarnings("unchecked")
  void onlyListIsTickedAndWhatAClassNeedsComesAlong() throws Exception {
    I18n.Language language = I18n.current();
    java.util.Locale locale = java.util.Locale.getDefault();
    I18n.set(I18n.Language.EN);
    LayoutIT.startFx();
    try {
      LayoutIT.onFx(() -> {
        var dialog = AbiturklassenDialog.build(Set.of("Queue"));
        var pane = dialog.getDialogPane();
        var boxes = (Map<String, CheckBox>) pane.getProperties().get("boxes");
        var along = (Label) pane.getProperties().get("along");
        dialog.show();

        assertThat(boxes.entrySet().stream().filter(e -> e.getValue().isSelected())
            .map(Map.Entry::getKey)).containsExactlyInAnyOrder("List", "Queue");
        assertThat(boxes.get("Queue").isDisabled()).as("already in the project").isTrue();
        assertThat(along.getText()).isEmpty();

        boxes.get("Graph").setSelected(true);
        assertThat(along.getText()).isEqualTo("Comes along, because it is needed: Vertex, Edge");
        boxes.get("List").setSelected(false);
        assertThat(along.getText()).endsWith(": List, Vertex, Edge");

        UiSmokeIT.snapshot(pane, Path.of("target/abiturklassen-dialog.png"));
        pane.getButtonTypes().stream().filter(b -> b.getButtonData()
            == javafx.scene.control.ButtonBar.ButtonData.OK_DONE).findFirst()
            .ifPresent(b -> ((javafx.scene.control.Button) pane.lookupButton(b)).fire());
        assertThat(dialog.getResult().classes()).containsExactly("Graph");
        assertThat(dialog.getResult().source()).isNull();
      });
    } finally {
      I18n.set(language);
      java.util.Locale.setDefault(locale);
    }
  }
}
