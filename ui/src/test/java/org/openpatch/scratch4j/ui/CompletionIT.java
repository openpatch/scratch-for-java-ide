package org.openpatch.scratch4j.ui;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.compile.Completions;
import org.openpatch.scratch4j.core.project.NewProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Completion labels and insertion (needs xvfb-run and -Dscratch4j.uismoke=true). */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class CompletionIT {

  @TempDir
  Path tmp;

  @Test
  void compiledLibraryMethodsUseTheNamesOfTheMatchingDocumentedOverload() throws Exception {
    assertLibraryLabels("this.mo", List.of("move(double steps)", "move(Vector2 v)"));
    assertLibraryLabels("this.addAni", List.of(
        "addAnimation(String name, String pattern, int frames)",
        "addAnimation(String name, Function<Integer,String> builder, int frame)"));
    assertLibraryLabels("this.isTouchingSp", List.of(
        "isTouchingSprite(Sprite sprite)", "isTouchingSprite(Class<? extends Sprite> c)"));
    assertLibraryLabels("Operators.ma", List.of("max(double[] v)", "max(int[] v)"));
    assertLibraryLabels("new Timer().intervalMi", List.of(
        "intervalMillis(int millis, boolean skipFirst)",
        "intervalMillis(int millis1, int millis2)"));
  }

  private void assertLibraryLabels(String receiver, List<String> expected) throws Exception {
    String text = """
        import org.openpatch.scratch.*;
        class Player extends AnimatedSprite {
          public void run() {
            %s
          }
        }
        """.formatted(receiver);
    Path file = tmp.resolve("Player.java");
    Files.writeString(file, text);
    int caret = text.indexOf(receiver) + receiver.length();
    var result = Completions.complete(file, text, caret, List.of(file),
        List.of(NewProject.classpathJar(org.openpatch.scratch.Sprite.class)));
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(file, text, ApiIndex.load(), () -> tmp);
      code.area().moveTo(caret);
      code.setSemanticCompleter((source, content, offset) -> result);
      var items = code.semanticCompletionsNow();
      assertThat(items).extracting(CodeEditor.Completion::label).containsAll(expected);
      for (var item : items) {
        assertThat(item.label()).doesNotContainPattern("\\barg\\d+\\b");
        assertThat(item.detail()).contains(item.label()).doesNotContainPattern("\\barg\\d+\\b");
      }
    });
  }

  /** Real keys and clicks (the robot): the suggestions go as soon as they no longer apply. */
  @Test
  void suggestionsCloseWhenTheCaretMovesAClickLandsOrTheWordIsGone() throws Exception {
    String text = """
        class Player {
          int mode;
          void move() {}
          void run() {
            mo
          }
        }
        """;
    int end = text.indexOf("    mo\n") + "    mo".length();
    AtomicBoolean pressed = new AtomicBoolean();
    Opened opened = openEditor(text, editor -> {
      Button other = new Button("Other");
      other.setOnAction(e -> pressed.set(true));
      return new BorderPane(editor, null, null, other, null);
    });
    Stage window = opened.window();
    CodeEditor code = opened.code();
    try {
      // a cursor key moves the caret as usual and closes them
      for (KeyCode key : List.of(KeyCode.LEFT, KeyCode.RIGHT, KeyCode.HOME, KeyCode.END,
          KeyCode.PAGE_UP, KeyCode.PAGE_DOWN)) {
        openSuggestions(window, code, end);
        LayoutIT.onFx(() -> new Robot().keyType(key));
        LayoutIT.settle();
        LayoutIT.onFx(() -> {
          assertThat(code.completionShowing()).as(key.getName()).isFalse();
          if (key == KeyCode.LEFT) {
            assertThat(code.area().getCaretPosition()).isEqualTo(end - 1);
          }
        });
      }

      // a click in the code closes them, and the caret goes where it landed
      openSuggestions(window, code, end);
      LayoutIT.onFx(() -> {
        int at = text.indexOf("mode");
        click(code.area().getCharacterBoundsOnScreen(at, at + 1).orElseThrow());
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(code.completionShowing()).isFalse();
        assertThat(code.caretLine()).isEqualTo(2);
      });

      // so does a click beside the code, which still does its job
      openSuggestions(window, code, end);
      LayoutIT.onFx(() -> {
        var other = ((BorderPane) window.getScene().getRoot()).getBottom();
        click(other.localToScreen(other.getBoundsInLocal()));
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(code.completionShowing()).isFalse();
        assertThat(pressed).as("the button was pressed").isTrue();
      });

      // deleting the word leaves nothing to complete
      openSuggestions(window, code, end);
      LayoutIT.onFx(() -> new Robot().keyType(KeyCode.BACK_SPACE));
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(code.completionShowing()).as("after m").isTrue());
      LayoutIT.onFx(() -> new Robot().keyType(KeyCode.BACK_SPACE));
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(code.completionShowing()).isFalse());
    } finally {
      LayoutIT.onFx(window::hide);
    }
  }

  /** javac's answer for suggestions that were closed meanwhile does not open them again. */
  @Test
  void aLateAnswerLeavesClosedSuggestionsClosed() throws Exception {
    String text = """
        class Player {
          int mode;
          void run() {
            this.
          }
        }
        """;
    int end = text.indexOf("this.") + "this.".length();
    Opened opened = openEditor(text, editor -> editor);
    Stage window = opened.window();
    CodeEditor code = opened.code();
    CountDownLatch answer = new CountDownLatch(1);
    try {
      LayoutIT.onFx(() -> code.setSemanticCompleter((source, content, offset) -> {
        try {
          answer.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return new Completions.Result(offset, "", true, List.of(new Completions.Item("mode",
            Completions.Kind.FIELD, "mode", "int", "Player", false, List.of())));
      }));
      openSuggestions(window, code, end);
      LayoutIT.onFx(() -> new Robot().keyType(KeyCode.ESCAPE));
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(code.completionShowing()).isFalse());
      answer.countDown();
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(code.completionShowing()).isFalse());
    } finally {
      answer.countDown();
      LayoutIT.onFx(window::hide);
    }
  }

  private record Opened(Stage window, CodeEditor code) {}

  /** A window with an editor for {@code text}, placed in the scene by {@code root}. */
  private Opened openEditor(String text,
      java.util.function.Function<CodeEditor, javafx.scene.Parent> root) throws Exception {
    Path file = tmp.resolve("Player.java");
    Files.writeString(file, text);
    AtomicReference<Opened> opened = new AtomicReference<>();
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      CodeEditor code = new CodeEditor(file, text, ApiIndex.load(), () -> tmp);
      Stage stage = new Stage();
      Scene scene = new Scene(root.apply(code), 760, 340);
      Theme.apply(scene);
      stage.setScene(scene);
      stage.show();
      opened.set(new Opened(stage, code));
    });
    LayoutIT.settle();
    return opened.get();
  }

  /** Ctrl+Space at {@code caret}, with the window and the code focused for the robot's keys. */
  private static void openSuggestions(Stage window, CodeEditor code, int caret)
      throws Exception {
    LayoutIT.onFx(() -> {
      window.toFront();
      window.requestFocus();
      code.area().requestFocus();
      code.area().moveTo(caret);
      code.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
          "", "", KeyCode.SPACE, false, true, false, false));
    });
    LayoutIT.settle();
    LayoutIT.onFx(() -> assertThat(code.completionShowing()).as("suggestions open").isTrue());
  }

  private static void click(javafx.geometry.Bounds onScreen) {
    Robot robot = new Robot();
    robot.mouseMove(onScreen.getCenterX(), onScreen.getCenterY());
    robot.mouseClick(MouseButton.PRIMARY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"move()", "move(int steps)", "move(String direction)"})
  void overloadsShowParametersAndInsertOnlyTheCall(String selectedLabel) throws Exception {
    String text = """
        class Player {
          int mode;
          void move() {}
          void move(int steps) {}
          void move(String direction) {}
          void run() {
            this.mo
          }
        }
        """;
    Path file = tmp.resolve("Player.java");
    Files.writeString(file, text);
    int caret = text.indexOf("this.mo") + "this.mo".length();
    var result = Completions.complete(file, text, caret, List.of(file), List.of());
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    LayoutIT.startFx();
    try {
      LayoutIT.onFx(() -> {
        CodeEditor code = new CodeEditor(file, text, ApiIndex.load(), () -> tmp);
        editor.set(code);
        code.area().moveTo(caret);
        // The initial name-based suggestions also display a method signature.
        var fallback = code.completions().stream().filter(c -> c.text().equals("move"))
            .findFirst().orElseThrow();
        assertThat(fallback.label()).startsWith("move(").endsWith(")");
        code.setSemanticCompleter((source, content, offset) -> result);
        Stage stage = new Stage();
        window.set(stage);
        Scene scene = new Scene(code, 760, 300);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.show();
        code.area().requestFocus();
        code.area().requestFollowCaret();
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> editor.get().area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
          "", "", KeyCode.SPACE, false, true, false, false)));
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        CodeEditor code = editor.get();
        assertThat(code.completionShowing()).isTrue();
        ListView<?> list = Window.getWindows().stream().filter(Window::isShowing)
            .map(w -> w.getScene().getRoot().lookup(".completion-list"))
            .filter(ListView.class::isInstance).map(n -> (ListView<?>) n)
            .findFirst().orElseThrow();
        var labels = list.getItems().stream().map(c -> ((CodeEditor.Completion) c).label())
            .toList();
        assertThat(labels).containsExactly("move()", "move(int steps)",
            "move(String direction)", "mode");
        assertThat(list.lookupAll(".list-cell").stream().filter(ListCell.class::isInstance)
            .map(n -> ((ListCell<?>) n).getText()).toList())
            .contains("move()", "move(int steps)", "move(String direction)");
        list.getSelectionModel().select(labels.indexOf(selectedLabel));
        code.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
            "", "", KeyCode.ENTER, false, false, false, false));
        assertThat(code.content()).isEqualTo(text.replace("this.mo\n", "this.move()\n"));
        int end = code.content().indexOf("this.move()") + "this.move()".length();
        assertThat(code.area().getCaretPosition())
            .isEqualTo(end - (selectedLabel.equals("move()") ? 0 : 1));
        assertThat(code.completionShowing()).isFalse();
      });
    } finally {
      LayoutIT.onFx(() -> {
        if (window.get() != null) {
          window.get().hide();
        }
      });
    }
  }
}
