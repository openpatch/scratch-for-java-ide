package org.openpatch.scratch4j.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A problem with a stack trace keeps it folded until clicked, then shows it
 * with a copy button (needs a display: {@code xvfb-run -a} and
 * {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class ProblemsPaneIT {

  @Test
  void stackTracesUnfoldOnClickAndCanBeCopied() throws Exception {
    I18n.Language language = I18n.current();
    I18n.set(I18n.Language.EN);
    LayoutIT.startFx();
    AtomicReference<Stage> stage = new AtomicReference<>();
    try {
      LayoutIT.onFx(() -> {
        var cause = new java.nio.file.ClosedFileSystemException();
        Problem crash = Problem.crash("java.lang.RuntimeException: " + cause,
            new RuntimeException(cause));
        ProblemsPane pane = new ProblemsPane(p -> { });
        pane.setProblems(List.of(crash));
        Scene scene = new Scene(pane, 640, 420);
        Theme.apply(scene);
        Stage window = new Stage();
        window.setScene(scene);
        window.show();
        stage.set(window);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        Node root = stage.get().getScene().getRoot();
        assertThat(root.lookup(".problem-trace")).as("folded at first").isNull();
        assertThat(root.lookup(".problem-trace-toggle")).isNotNull();

        @SuppressWarnings("unchecked")
        ListView<Problem> list = (ListView<Problem>) root.lookup(".problems");
        list.getSelectionModel().select(0);
        click(list);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        Node root = stage.get().getScene().getRoot();
        TextArea trace = (TextArea) root.lookup(".problem-trace");
        assertThat(trace).as("unfolded by the click").isNotNull();
        assertThat(trace.getText()).startsWith("java.lang.RuntimeException")
            .contains("Caused by: java.nio.file.ClosedFileSystemException", "ProblemsPaneIT");
        UiSmokeIT.snapshot(root, Path.of("target/problems-trace.png"));

        click(trace); // selecting text keeps it open
        ((Button) root.lookup(".problem-trace-copy")).fire();
        assertThat(Clipboard.getSystemClipboard().getString())
            .contains("Caused by: java.nio.file.ClosedFileSystemException");
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        Node root = stage.get().getScene().getRoot();
        assertThat(root.lookup(".problem-trace")).as("still open").isNotNull();
        click(root.lookup(".problems"));
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(stage.get().getScene().getRoot().lookup(".problem-trace"))
          .as("folded again").isNull());
    } finally {
      I18n.set(language);
      if (stage.get() != null) {
        LayoutIT.onFx(() -> stage.get().close());
      }
    }
  }

  @Test
  void errorDialogsFoldTheStackTraceUnderShowDetails() throws Exception {
    I18n.Language language = I18n.current();
    I18n.set(I18n.Language.EN);
    LayoutIT.startFx();
    AtomicReference<javafx.scene.control.Alert> shown = new AtomicReference<>();
    try {
      LayoutIT.onFx(() -> {
        var e = new java.io.IOException("Archive has 20,001 files; at most 20,000 can be imported",
            new IllegalStateException("inner"));
        var alert = ErrorDetails.attach(new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.ERROR, e.getMessage(), I18n.ok()), e);
        alert.setHeaderText(null);
        Theme.style(alert);
        alert.show();
        shown.set(alert);
        assertThat(alert.getDialogPane().isExpanded()).as("folded at first").isFalse();
        alert.getDialogPane().setExpanded(true);
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        var pane = shown.get().getDialogPane();
        TextArea trace = (TextArea) pane.lookup(".problem-trace");
        assertThat(trace.getText()).startsWith("java.io.IOException: Archive has 20,001 files")
            .contains("Caused by: java.lang.IllegalStateException: inner");
        assertThat(pane.lookup(".problem-trace-copy")).isNotNull();
        UiSmokeIT.snapshot(pane, Path.of("target/error-dialog-details.png"));
      });
    } finally {
      I18n.set(language);
      if (shown.get() != null) {
        LayoutIT.onFx(() -> shown.get().close());
      }
    }
  }

  private static void click(Node node) {
    var at = node.localToScene(10, 10);
    javafx.event.Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_CLICKED, at.getX(),
        at.getY(), 0, 0, MouseButton.PRIMARY, 1, false, false, false, false, false, false,
        false, true, false, true, null));
  }
}
