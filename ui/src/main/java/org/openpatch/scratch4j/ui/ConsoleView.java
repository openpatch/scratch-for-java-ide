package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The program console: stdout, stderr in red, IDE messages in grey, the
 * library's beginner asset errors highlighted, and stack-trace lines like
 * {@code at Player.run(Player.java:12)} clickable to jump into the editor.
 */
final class ConsoleView extends BorderPane {

  private static final Pattern TRACE = Pattern.compile("\\(([\\w$]+\\.java):(\\d+)\\)");
  private static final int MAX_CHARS = 400_000;

  private final StyleClassedTextArea text = new StyleClassedTextArea();

  /** {@code onOpen} receives a source file name and a 1-based line. */
  ConsoleView(BiConsumer<String, Integer> onOpen) {
    getStyleClass().add("console");
    text.setEditable(false);
    text.setWrapText(true);
    text.getStyleClass().add("console-text");
    text.setOnMouseClicked(e -> {
      var hit = text.hit(e.getX(), e.getY());
      int paragraph = text.offsetToPosition(hit.getInsertionIndex(),
          org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
      Matcher m = TRACE.matcher(text.getParagraph(paragraph).getText());
      if (m.find()) {
        onOpen.accept(m.group(1), Integer.parseInt(m.group(2)));
      }
    });
    text.setOnMouseMoved(e -> {
      var hit = text.hit(e.getX(), e.getY());
      List<String> style = hit.getInsertionIndex() < text.getLength()
          ? List.copyOf(text.getStyleOfChar(hit.getInsertionIndex())) : List.of();
      text.setCursor(style.contains("link") ? Cursor.HAND : Cursor.TEXT);
    });

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox bar = new HBox(4, spacer,
        Icons.button("fth-trash-2", I18n.t("console.clear"), this::clear));
    bar.setAlignment(Pos.CENTER_RIGHT);
    bar.getStyleClass().add("console-bar");
    setCenter(new VirtualizedScrollPane<>(text));
    setTop(bar);
  }

  void clear() {
    text.clear();
  }

  void out(String line) {
    append(line, "out");
  }

  void err(String line) {
    append(line, isAssetHint(line) ? "hint" : "err");
  }

  /** A tip the student should not miss (orange, bold). */
  void hint(String line) {
    append(line, "hint");
  }

  /** IDE messages (compiling..., program stopped). */
  void info(String line) {
    append(line, "info");
  }

  private static boolean isAssetHint(String line) {
    String lower = line.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("did you mean") || lower.contains("could not load")
        || lower.contains("not found") && (lower.contains("costume") || lower.contains("sound")
            || lower.contains("image") || lower.contains("backdrop"));
  }

  private void append(String line, String style) {
    Platform.runLater(() -> {
      int start = text.getLength();
      text.appendText(line + "\n");
      text.setStyleClass(start, start + line.length(), style);
      Matcher m = TRACE.matcher(line);
      if (m.find()) {
        text.setStyle(start + m.start(1), start + m.end(2), List.of(style, "link"));
      }
      if (text.getLength() > MAX_CHARS) {
        text.deleteText(0, text.getLength() - MAX_CHARS);
      }
      text.moveTo(text.getLength());
      text.requestFollowCaret();
    });
  }

  String content() {
    return text.getText();
  }
}
