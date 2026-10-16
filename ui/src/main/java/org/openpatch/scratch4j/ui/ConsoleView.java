package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.util.UndoUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
  private static final int MAX_PENDING_LINES = 4096;
  private static final int MAX_LINE_CHARS = 16_384;
  private static final int BATCH_CHARS = 8192;
  private static final int BATCH_LINES = 256;

  private final StyleClassedTextArea text = new StyleClassedTextArea();
  private final ArrayDeque<Output> pending = new ArrayDeque<>();
  private int pendingChars;
  private boolean scheduled;
  private boolean omitted;
  private long generation;
  private final javafx.animation.PauseTransition nextBatch =
      new javafx.animation.PauseTransition(javafx.util.Duration.millis(16));

  private record Output(String text, String style, int linkStart, int linkEnd) {
    Output tail(int from) {
      return new Output(text.substring(from), style,
          Math.max(0, linkStart - from), Math.max(0, linkEnd - from));
    }
  }

  /** {@code onOpen} receives a source file name and a 1-based line. */
  ConsoleView(BiConsumer<String, Integer> onOpen) {
    getStyleClass().add("console");
    text.setEditable(false);
    text.setUndoManager(UndoUtils.noOpUndoManager());
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
    // while nothing ran yet, the empty console says what the green flag does
    Label empty = new Label(I18n.t("console.empty"), Icons.of("fth-flag", 18));
    empty.getStyleClass().add("console-empty");
    empty.setWrapText(true);
    empty.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
    empty.setMouseTransparent(true);
    empty.visibleProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
        () -> text.getLength() == 0, text.lengthProperty()));
    setCenter(new StackPane(new VirtualizedScrollPane<>(text), empty));
    setTop(bar);
  }

  void clear() {
    nextBatch.stop();
    synchronized (pending) {
      generation++;
      pending.clear();
      pendingChars = 0;
      omitted = false;
      scheduled = false;
    }
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
    // Bound retained output before posting any UI work, including a single
    // enormous println. One scheduled drain serves all producer threads.
    boolean truncated = line.length() >= MAX_LINE_CHARS;
    if (truncated) line = line.substring(line.length() - MAX_LINE_CHARS + 1);
    Matcher trace = TRACE.matcher(line);
    boolean link = trace.find();
    Output output = new Output(line + "\n", style,
        link ? trace.start(1) : 0, link ? trace.end(2) : 0);
    synchronized (pending) {
      omitted |= truncated;
      while (!pending.isEmpty() && (pendingChars + output.text().length() > MAX_CHARS
          || pending.size() >= MAX_PENDING_LINES)) {
        pendingChars -= pending.removeFirst().text().length();
        omitted = true;
      }
      pending.addLast(output);
      pendingChars += output.text().length();
      if (!scheduled) {
        scheduled = true;
        long current = generation;
        Platform.runLater(() -> drain(current));
      }
    }
  }

  private void drain(long current) {
    List<Output> batch = new ArrayList<>();
    synchronized (pending) {
      if (current != generation) return; // cleared while a callback was waiting
      int remaining = BATCH_CHARS;
      if (omitted) {
        Output notice = new Output(I18n.t("console.omitted") + "\n", "info", 0, 0);
        batch.add(notice);
        remaining -= notice.text().length();
        omitted = false;
      }
      while (!pending.isEmpty() && remaining > 0 && batch.size() < BATCH_LINES) {
        Output output = pending.removeFirst();
        int length = Math.min(remaining, output.text().length());
        batch.add(new Output(output.text().substring(0, length), output.style(),
            Math.min(length, output.linkStart()), Math.min(length, output.linkEnd())));
        if (length < output.text().length()) pending.addFirst(output.tail(length));
        pendingChars -= length;
        remaining -= length;
      }
    }
    if (!batch.isEmpty()) {
      StringBuilder content = new StringBuilder();
      StyleSpansBuilder<Collection<String>> styles = new StyleSpansBuilder<>();
      for (Output output : batch) {
        content.append(output.text());
        styles.add(List.of(output.style()), output.linkStart());
        styles.add(List.of(output.style(), "link"), output.linkEnd() - output.linkStart());
        styles.add(List.of(output.style()), output.text().length() - output.linkEnd());
      }
      int start = text.getLength();
      text.appendText(content.toString());
      text.setStyleSpans(start, styles.create());
      if (text.getLength() > MAX_CHARS) text.deleteText(0, text.getLength() - MAX_CHARS);
      text.moveTo(text.getLength());
      text.requestFollowCaret();
    }
    synchronized (pending) {
      if (pending.isEmpty()) {
        scheduled = false;
      } else {
        // Yield to input and rendering between bounded batches, including
        // when a student program prints faster than the console can draw.
        nextBatch.setOnFinished(e -> drain(current));
        nextBatch.playFromStart();
      }
    }
  }

  String content() {
    return text.getText();
  }
}
