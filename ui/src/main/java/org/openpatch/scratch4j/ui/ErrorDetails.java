package org.openpatch.scratch4j.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The stack trace behind an error message: folded away under a problem or an
 * error dialog until asked for, then shown selectable with a button that
 * copies all of it (for a bug report).
 */
final class ErrorDetails {
  private ErrorDetails() {}

  static String trace(Throwable e) {
    StringWriter trace = new StringWriter();
    e.printStackTrace(new PrintWriter(trace));
    return trace.toString();
  }

  /** Puts {@code e}'s stack trace into the alert's "Show details" area; no-op without one. */
  static Alert attach(Alert alert, Throwable e) {
    if (e != null) {
      VBox box = box(trace(e));
      box.setPrefWidth(560);
      alert.getDialogPane().setExpandableContent(box);
    }
    return alert;
  }

  /** The stack trace, selectable, with a button that copies all of it. */
  static VBox box(String trace) {
    TextArea area = new TextArea(trace.strip());
    area.setEditable(false);
    area.setWrapText(false);
    area.getStyleClass().add("problem-trace");
    area.setPrefRowCount((int) Math.min(14, trace.lines().count()));
    area.setPrefColumnCount(20); // the space around decides, not the longest frame
    area.setMinWidth(0);
    area.setMinHeight(Region.USE_PREF_SIZE);
    Button copy = new Button(I18n.t("problems.trace.copy"), Icons.of("fth-copy"));
    copy.getStyleClass().add("problem-trace-copy");
    copy.setOnAction(e -> {
      ClipboardContent content = new ClipboardContent();
      content.putString(trace);
      Clipboard.getSystemClipboard().setContent(content);
      copy.setText(I18n.t("problems.trace.copied"));
    });
    VBox box = new VBox(6, area, copy);
    box.setMinWidth(0);
    // selecting text or copying must not fold the trace away again
    box.addEventHandler(MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
    return box;
  }
}
