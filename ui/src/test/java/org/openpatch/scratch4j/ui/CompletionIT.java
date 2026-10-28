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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.compile.Completions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.openpatch.scratch4j.core.project.LibraryJarSource;

/** Completion labels and insertion (needs xvfb-run and -Dscratch4j.uismoke=true). */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class CompletionIT {

  @TempDir
  Path tmp;

  private Prefs.Completion completion;

  /** Most of these tests type and expect suggestions: the while-typing setting. */
  @BeforeEach
  void suggestWhileTyping() throws Exception {
    completion = CodeEditor.COMPLETION.get();
    LayoutIT.startFx();
    LayoutIT.onFx(() -> CodeEditor.COMPLETION.set(Prefs.Completion.AUTO));
  }

  @AfterEach
  void restoreSuggestions() throws Exception {
    LayoutIT.onFx(() -> CodeEditor.COMPLETION.set(completion));
  }

  @Test
  void compiledLibraryMethodsUseTheNamesOfTheMatchingDocumentedOverload() throws Exception {
    // Scratch for Java 7's overloads
    assertLibraryLabels("this.mo", List.of("move(double steps)"));
    assertLibraryLabels("this.addAni", List.of(
        "addAnimation(String name, String pattern, int frames)",
        "addAnimation(String name, String sheetPath, int width, int height, int[] frames)"));
    assertLibraryLabels("this.isTouchingSp", List.of(
        "isTouchingSprite(Sprite sprite)", "isTouchingSprite(Class<? extends Sprite> type)"));
    assertLibraryLabels("Operators.ma", List.of("max(double[] v)", "max(int[] v)"));
    assertLibraryLabels("new Timer().interv", List.of(
        "interval(double seconds)", "interval(double on, double off)"));
  }

  private void assertLibraryLabels(String receiver, List<String> expected) throws Exception {
    String text = """
        import org.openpatch.scratch.*;
        class Player extends Sprite {
          public void run() {
            %s
          }
        }
        """.formatted(receiver);
    Path file = tmp.resolve("Player.java");
    Files.writeString(file, text);
    int caret = text.indexOf(receiver) + receiver.length();
    var result = Completions.complete(file, text, caret, List.of(file),
        List.of(LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"))));
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
    CountDownLatch started = new CountDownLatch(1);
    try {
      LayoutIT.onFx(() -> code.setSemanticCompleter((source, content, offset) -> {
        started.countDown();
        try {
          answer.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return new Completions.Result(offset, "", true, List.of(new Completions.Item("mode",
            Completions.Kind.FIELD, "mode", "int", "Player", false, List.of())));
      }));
      LayoutIT.onFx(() -> {
        window.toFront();
        window.requestFocus();
        code.area().requestFocus();
        code.area().moveTo(end);
        code.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
            "", "", KeyCode.SPACE, false, true, false, false));
      });
      assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
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

  @Test
  void fastTypingAsksJavacOncePerIdentifierNotOncePerKey() throws Exception {
    String text = """
        class Player {
          void run() {
            boolean b = false
          }
        }
        """;
    int end = text.indexOf("false") + "false".length();
    Opened opened = openEditor(text, editor -> editor);
    CodeEditor code = opened.code();
    java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
    try {
      LayoutIT.onFx(() -> {
        // a slow machine: every javac run takes a while
        code.setSemanticCompleter((source, content, offset) -> {
          runs.incrementAndGet();
          try {
            Thread.sleep(400);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          // like javac after "this.": the members, among them the one being typed
          return new Completions.Result(offset, "", true, List.of(new Completions.Item(
              "isKeyPressed", Completions.Kind.METHOD, "isKeyPressed(int keyCode)", "boolean",
              "org.openpatch.scratch.Sprite", false, List.of("int"))));
        });
        code.area().requestFocus();
        code.area().moveTo(end);
      });
      // a fast typist: a key every 80 ms
      for (char c : " || this.isKeyPressed(".toCharArray()) {
        LayoutIT.onFx(() -> code.area().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
            String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false)));
        Thread.sleep(80);
      }
      Thread.sleep(1500);
      LayoutIT.settle();
      // before: one whole-project analysis per key (10 here)
      assertThat(runs.get()).isBetween(1, 2);
    } finally {
      LayoutIT.onFx(opened.window()::hide);
    }
  }

  /** Held Backspace deletes on: it neither opens the suggestions nor asks javac. */
  @Test
  void backspaceDeletesWithoutSuggestions() throws Exception {
    String text = """
        class Player {
          void run() {
            moveSteps
          }
        }
        """;
    int end = text.indexOf("moveSteps") + "moveSteps".length();
    Opened opened = openEditor(text, editor -> editor);
    CodeEditor code = opened.code();
    java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
    try {
      LayoutIT.onFx(() -> {
        code.setSemanticCompleter((source, content, offset) -> {
          runs.incrementAndGet();
          return new Completions.Result(offset, "", false, List.of(new Completions.Item(
              "move", Completions.Kind.METHOD, "move()", "void", "Player", false, List.of())));
        });
        code.area().requestFocus();
        code.area().moveTo(end);
        // as JavaFX sends it: the key, then "\b" as the typed character
        for (int i = 0; i < 7; i++) {
          code.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
              "", "", KeyCode.BACK_SPACE, false, false, false, false));
          code.area().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
              "\b", "", KeyCode.UNDEFINED, false, false, false, false));
        }
      });
      Thread.sleep(300);
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(code.content()).contains("    mo\n");
        assertThat(code.completionShowing()).isFalse();
      });
      assertThat(runs.get()).isZero();
    } finally {
      LayoutIT.onFx(opened.window()::hide);
    }
  }

  /** javac is asked at the first letter, so the second one opens the list at once. */
  @Test
  void theFirstLetterAsksJavacAndTheSecondShowsTheAnswer() throws Exception {
    String text = """
        class Player {
          void run() {
           \s
          }
        }
        """;
    int caret = text.indexOf("   \n") + 3;
    Opened opened = openEditor(text, editor -> editor);
    CodeEditor code = opened.code();
    java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
    try {
      LayoutIT.onFx(() -> {
        code.setSemanticCompleter((source, content, offset) -> {
          runs.incrementAndGet();
          return new Completions.Result(offset, "", false, List.of(
              new Completions.Item("getX", Completions.Kind.METHOD, "getX()", "double",
                  "org.openpatch.scratch.Sprite", false, List.of()),
              new Completions.Item("goToRandomPosition", Completions.Kind.METHOD,
                  "goToRandomPosition()", "void", "org.openpatch.scratch.Sprite", false,
                  List.of())));
        });
        code.area().requestFocus();
        code.area().moveTo(caret);
        code.area().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
            "g", "", KeyCode.UNDEFINED, false, false, false, false));
      });
      Thread.sleep(300);
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(runs.get()).isOne();
        assertThat(code.completionShowing()).as("one letter does not open the list").isFalse();
        code.area().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
            "X", "", KeyCode.UNDEFINED, false, false, false, false));
      });
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(code.completionShowing()).isTrue();
        // gX: the starts of getX's words, not goToRandomPosition's
        assertThat(code.completionCandidates()).containsExactly("getX");
      });
      assertThat(runs.get()).isOne();
    } finally {
      LayoutIT.onFx(opened.window()::hide);
    }
  }

  /** Settings > Suggestions: only with Ctrl+Space types without asking javac; off ignores it. */
  @Test
  void suggestionsWaitForCtrlSpaceOrStayOffAsSet() throws Exception {
    String text = """
        class Player {
          void run() {
           \s
          }
        }
        """;
    int caret = text.indexOf("   \n") + 3;
    Opened opened = openEditor(text, editor -> editor);
    CodeEditor code = opened.code();
    java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
    Prefs.Completion before = CodeEditor.COMPLETION.get();
    try {
      LayoutIT.onFx(() -> {
        CodeEditor.COMPLETION.set(Prefs.Completion.MANUAL);
        code.setSemanticCompleter((source, content, offset) -> {
          runs.incrementAndGet();
          return new Completions.Result(offset, "", false, List.of(
              new Completions.Item("getX", Completions.Kind.METHOD, "getX()", "double",
                  "org.openpatch.scratch.Sprite", false, List.of())));
        });
        code.area().requestFocus();
        code.area().moveTo(caret);
        for (String ch : List.of("g", "e", ".")) {
          code.area().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
              ch, "", KeyCode.UNDEFINED, false, false, false, false));
        }
      });
      Thread.sleep(300);
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(code.completionShowing()).as("typing does not open the list").isFalse();
        assertThat(runs.get()).isZero();
        CodeEditor.COMPLETION.set(Prefs.Completion.OFF);
        code.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
            "", "", KeyCode.SPACE, false, true, false, false));
      });
      Thread.sleep(300);
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(code.completionShowing()).as("off: Ctrl+Space too").isFalse());
      LayoutIT.onFx(() -> CodeEditor.COMPLETION.set(Prefs.Completion.MANUAL));
      openSuggestions(opened.window(), code, caret + 2);
    } finally {
      LayoutIT.onFx(() -> CodeEditor.COMPLETION.set(before));
      LayoutIT.onFx(opened.window()::hide);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"held", "Random", ""})
  void suggestionsWaitForTheReceiverAndScope(String receiver) throws Exception {
    String text = """
        import org.openpatch.scratch.*;
        class Player extends Sprite {
          void example() {
            Sprite held = this;
            %s
          }
        }
        """.formatted(receiver);
    int caret = text.indexOf("    " + receiver + "\n") + ("    " + receiver).length();
    Opened opened = openEditor(text, editor -> editor);
    CodeEditor code = opened.code();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch answer = new CountDownLatch(1);
    CountDownLatch analyzed = new CountDownLatch(1);
    try {
      LayoutIT.onFx(() -> {
        code.setSemanticCompleter((source, content, offset) -> {
          started.countDown();
          try {
            answer.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException(e);
          }
          var result = Completions.complete(source, content, offset, List.of(source),
              List.of(LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"))));
          analyzed.countDown();
          return result;
        });
        code.area().requestFocus();
        code.area().moveTo(caret);
        for (char c : (receiver.isEmpty() ? "ra" : ".").toCharArray()) {
          code.area().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
              String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false));
        }
      });
      LayoutIT.settle();
      assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      LayoutIT.onFx(() -> {
        assertThat(code.completionCandidates()).as("only type-checked names are offered").isEmpty();
        assertThat(code.completionShowing()).isFalse();
      });
      answer.countDown();
      assertThat(analyzed.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(code.completionShowing()).isTrue();
        if (receiver.equals("held")) {
          assertThat(code.completionCandidates()).contains("move", "goToRandomPosition")
              .doesNotContain("random", "randomInt", "randomVector2");
        } else if (receiver.equals("Random")) {
          assertThat(code.completionCandidates()).contains("random", "randomInt", "randomVector2")
              .doesNotContain("move", "goToRandomPosition");
        } else {
          assertThat(code.completionCandidates()).contains("Random")
              .doesNotContain("random", "randomInt", "randomVector2");
        }
      });
      for (char c : (receiver.isEmpty() ? "ndom" : "random").toCharArray()) {
        LayoutIT.onFx(() -> code.area().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
            String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false)));
      }
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        if (receiver.equals("held")) {
          assertThat(code.completionCandidates()).isEmpty();
          assertThat(code.completionShowing()).as("held.random is not a Sprite member").isFalse();
        } else {
          assertThat(code.completionShowing()).isTrue();
          if (receiver.equals("Random")) {
            assertThat(code.completionCandidates()).contains("random", "randomInt", "randomVector2");
          } else {
            assertThat(code.completionCandidates()).containsExactly("Random");
          }
        }
      });
    } finally {
      answer.countDown();
      LayoutIT.onFx(opened.window()::hide);
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
  @ValueSource(strings = {"random()", "random(int max)"})
  void fullyTypedMethodStaysOpenAndInsertsTheCall(String signature) throws Exception {
    String text = """
        class Held {
          int %s { return 0; }
        }
        class Player {
          Held held = new Held();
          void run() {
            held.ra
          }
        }
        """.formatted(signature);
    int caret = text.indexOf("held.ra") + "held.ra".length();
    Opened opened = openEditor(text, editor -> editor);
    CodeEditor code = opened.code();
    CountDownLatch analyzed = new CountDownLatch(1);
    try {
      LayoutIT.onFx(() -> code.setSemanticCompleter((source, content, offset) -> {
        var result = Completions.complete(source, content, offset, List.of(source), List.of());
        analyzed.countDown();
        return result;
      }));
      openSuggestions(opened.window(), code, caret);
      assertThat(analyzed.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      LayoutIT.settle();
      LayoutIT.onFx(() -> assertThat(code.completions())
          .extracting(CodeEditor.Completion::label).containsExactly(signature));
      for (char c : "ndom".toCharArray()) {
        LayoutIT.onFx(() -> code.area().fireEvent(new KeyEvent(KeyEvent.KEY_TYPED,
            String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false)));
      }
      LayoutIT.settle();
      LayoutIT.onFx(() -> {
        assertThat(code.completionShowing()).as("held.random remains a callable suggestion")
            .isTrue();
        code.area().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED,
            "", "", KeyCode.ENTER, false, false, false, false));
        assertThat(code.content()).isEqualTo(text.replace("held.ra\n", "held.random()\n"));
        int end = code.content().indexOf("held.random()") + "held.random()".length();
        assertThat(code.area().getCaretPosition())
            .isEqualTo(end - (signature.equals("random()") ? 0 : 1));
        assertThat(code.completionShowing()).isFalse();
      });
    } finally {
      LayoutIT.onFx(opened.window()::hide);
    }
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
