package org.openpatch.scratch4j.ui;

import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;

/** Which key presses an editor tab may take as its own shortcuts. */
final class EditorKeys {

  private EditorKeys() {}

  /**
   * Whether {@code e} is the editor's: text fields keep every key, and lists,
   * combo boxes and spinners keep plain keys (arrows, Delete, letters).
   */
  static boolean isEditorKey(KeyEvent e) {
    Object target = e.getTarget();
    if (target instanceof TextInputControl) {
      return false;
    }
    return e.isShortcutDown() || !(target instanceof ComboBoxBase<?>
        || target instanceof ListView<?> || target instanceof Spinner<?>);
  }
}
