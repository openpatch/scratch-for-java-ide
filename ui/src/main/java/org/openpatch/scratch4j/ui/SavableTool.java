package org.openpatch.scratch4j.ui;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.scene.control.Alert;

/**
 * A tool tab with its own Save (image, sound, map): the tab marks unsaved
 * edits like a code tab and asks before closing them away.
 */
interface SavableTool {

  /** True while there are edits since the last save. */
  ReadOnlyBooleanProperty dirtyProperty();

  /** Writes the edits to the file (Ctrl+S, File > Save). */
  void save();

  /** The question asked when the tab closes with unsaved edits. */
  String unsavedCloseQuestion();

  /** Asks before closing with unsaved edits; true when the tab may close. */
  default boolean confirmClose() {
    if (!dirtyProperty().get()) {
      return true;
    }
    Alert ask = new Alert(Alert.AlertType.CONFIRMATION, unsavedCloseQuestion(),
        I18n.ok(), I18n.cancel());
    ask.setHeaderText(null);
    Theme.style(ask);
    return ask.showAndWait().orElse(null) == I18n.ok();
  }
}
