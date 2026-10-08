package org.openpatch.scratch4j.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.openpatch.scratch4j.core.lesson.Lesson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Help > For teachers > Lesson for this project: writes the project's
 * {@code .scratch4j/lesson.json} without touching JSON. Steps are listed on
 * the left; the selected one is edited on the right: its title and text in
 * English and German, code to copy, and when it is done (the program ran,
 * the code contains something, or a number changed). "Test with the current
 * code" shows at once whether the check would tick off now.
 */
final class LessonEditorDialog {

  /** What the dialog ended with. */
  sealed interface Outcome {}

  record Saved(Lesson lesson) implements Outcome {}

  record Removed() implements Outcome {}

  enum Kind { RAN, CONTAINS, CHANGED, ADVANCED }

  /** One step while it is edited. */
  static final class Draft {
    String id;
    final Map<String, String> title = new LinkedHashMap<>();
    final Map<String, String> text = new LinkedHashMap<>();
    String code = "";
    Kind kind = Kind.RAN;
    String file = "";
    String pieces = "";
    boolean regex;
    String changedPattern = "";
    String from = "";
    Lesson.Check advanced;

    static Draft of(Lesson.Step step) {
      Draft d = new Draft();
      d.id = step.id();
      d.title.putAll(step.title());
      d.text.putAll(step.text());
      d.code = step.code() == null ? "" : step.code();
      Lesson.Check check = step.check();
      List<Lesson.Contains> contains = containsList(check);
      if (check instanceof Lesson.Ran) {
        d.kind = Kind.RAN;
      } else if (check instanceof Lesson.Changed c) {
        d.kind = Kind.CHANGED;
        d.file = c.file();
        d.changedPattern = c.pattern();
        d.from = c.from();
      } else if (contains != null) {
        d.kind = Kind.CONTAINS;
        d.file = contains.get(0).file();
        d.regex = !contains.get(0).literal();
        d.pieces = String.join("\n", contains.stream().map(Lesson.Contains::pattern).toList());
      } else {
        d.kind = Kind.ADVANCED;
        d.advanced = check;
      }
      return d;
    }

    /** Contains checks on one file, all plain or all regex: editable as one list. */
    private static List<Lesson.Contains> containsList(Lesson.Check check) {
      List<Lesson.Check> parts = check instanceof Lesson.All all ? all.checks() : List.of(check);
      List<Lesson.Contains> out = new ArrayList<>();
      for (Lesson.Check part : parts) {
        if (!(part instanceof Lesson.Contains c)) {
          return null;
        }
        out.add(c);
      }
      boolean sameFile = out.stream().map(Lesson.Contains::file).distinct().count() == 1;
      boolean sameKind = out.stream().map(Lesson.Contains::literal).distinct().count() == 1;
      return !out.isEmpty() && sameFile && sameKind ? out : null;
    }

    /** The step, or an error message key when something is missing. */
    Object toStep() {
      if (title.getOrDefault("en", "").isBlank()) {
        return "lessoneditor.error.title";
      }
      Lesson.Check check;
      switch (kind) {
        case RAN -> check = new Lesson.Ran();
        case CONTAINS -> {
          List<String> lines = pieces.lines().map(String::strip).filter(l -> !l.isEmpty())
              .toList();
          if (file.isBlank() || lines.isEmpty()) {
            return "lessoneditor.error.contains";
          }
          List<Lesson.Check> checks = new ArrayList<>();
          for (String line : lines) {
            if (regex && !validRegex(line)) {
              return "lessoneditor.error.regex";
            }
            checks.add(new Lesson.Contains(file.strip(), line, !regex));
          }
          check = checks.size() == 1 ? checks.get(0) : new Lesson.All(List.copyOf(checks));
        }
        case CHANGED -> {
          if (file.isBlank() || changedPattern.isBlank() || from.isBlank()) {
            return "lessoneditor.error.changed";
          }
          if (!validRegex(changedPattern) || Pattern.compile(changedPattern).matcher("")
              .groupCount() < 1) {
            return "lessoneditor.error.group";
          }
          check = new Lesson.Changed(file.strip(), changedPattern.strip(), from.strip());
        }
        default -> check = advanced;
      }
      return new Lesson.Step(id, Map.copyOf(trimmed(title)), Map.copyOf(trimmed(text)),
          code.isBlank() ? null : code, check);
    }

    private static Map<String, String> trimmed(Map<String, String> texts) {
      Map<String, String> out = new LinkedHashMap<>();
      texts.forEach((k, v) -> {
        if (v != null && !v.isBlank()) out.put(k, v.strip());
      });
      return out;
    }

    private static boolean validRegex(String regex) {
      try {
        Pattern.compile(regex);
        return true;
      } catch (java.util.regex.PatternSyntaxException e) {
        return false;
      }
    }
  }

  private LessonEditorDialog() {}

  /**
   * Edits {@code lesson} (null: a new one) of the project with these Java
   * files; {@code facts} gives the project as it is now for the test button.
   */
  static Optional<Outcome> show(Window owner, Lesson lesson, String newId,
      List<String> javaFiles, Supplier<Lesson.Facts> facts) {
    Dialog<Outcome> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(I18n.t("lessoneditor.title"));
    dialog.setHeaderText(null);
    dialog.setResizable(true);
    Theme.style(dialog);
    ButtonType save = new ButtonType(I18n.t("lessoneditor.save"), ButtonBar.ButtonData.OK_DONE);
    ButtonType remove = new ButtonType(I18n.t("lessoneditor.remove"),
        ButtonBar.ButtonData.LEFT);
    dialog.getDialogPane().getButtonTypes().addAll(save, I18n.cancel());
    if (lesson != null) {
      dialog.getDialogPane().getButtonTypes().add(remove);
    }

    // the lesson's title
    TextField titleEn = new TextField(lesson == null ? "" : lesson.title().getOrDefault("en", ""));
    TextField titleDe = new TextField(lesson == null ? "" : lesson.title().getOrDefault("de", ""));
    titleEn.setPromptText(I18n.t("lessoneditor.lessontitle.prompt"));
    GridPane head = new GridPane();
    head.setHgap(8);
    head.setVgap(6);
    head.addRow(0, label("lessoneditor.lessontitle"), language("EN"), titleEn,
        language("DE"), titleDe);
    GridPane.setHgrow(titleEn, Priority.ALWAYS);
    GridPane.setHgrow(titleDe, Priority.ALWAYS);

    // the steps
    ObservableList<Draft> drafts = FXCollections.observableArrayList();
    if (lesson == null) {
      Draft first = new Draft();
      first.id = "run";
      first.title.put("en", "Start your program");
      first.title.put("de", "Starte dein Programm");
      first.text.put("en", "Press the green flag at the top (or F5).");
      first.text.put("de", "Drücke oben die grüne Flagge (oder F5).");
      drafts.add(first);
    } else {
      lesson.steps().forEach(step -> drafts.add(Draft.of(step)));
    }
    ListView<Draft> list = new ListView<>(drafts);
    list.setPrefWidth(230);
    list.setCellFactory(v -> new ListCell<>() {
      @Override protected void updateItem(Draft d, boolean empty) {
        super.updateItem(d, empty);
        setText(empty || d == null ? null
            : (getIndex() + 1) + ". " + d.title.getOrDefault("en", "").strip());
      }
    });
    Button add = Icons.button("fth-plus", I18n.t("lessoneditor.add"), () -> {
      Draft d = new Draft();
      d.id = freeId(drafts);
      d.title.put("en", I18n.t("lessoneditor.newstep"));
      int at = list.getSelectionModel().getSelectedIndex() + 1;
      drafts.add(at <= 0 ? drafts.size() : at, d);
      list.getSelectionModel().select(d);
    });
    Button delete = Icons.button("fth-trash-2", I18n.t("lessoneditor.delete"), () -> {
      Draft d = list.getSelectionModel().getSelectedItem();
      if (d != null && drafts.size() > 1) drafts.remove(d);
    });
    Button up = Icons.button("fth-arrow-up", I18n.t("lessoneditor.up"), () -> move(list, -1));
    Button down = Icons.button("fth-arrow-down", I18n.t("lessoneditor.down"), () -> move(list, 1));
    HBox listButtons = new HBox(4, add, delete, up, down);
    VBox.setVgrow(list, Priority.ALWAYS);
    VBox left = new VBox(6, label("lessoneditor.steps"), list, listButtons);

    // the selected step
    TextField stepTitleEn = new TextField();
    TextField stepTitleDe = new TextField();
    TextArea textEn = area(3);
    TextArea textDe = area(3);
    TextArea code = area(4);
    code.setWrapText(false);
    code.getStyleClass().add("lesson-code");
    code.setPromptText(I18n.t("lessoneditor.code.prompt"));
    ComboBox<Kind> kind = new ComboBox<>(FXCollections.observableArrayList(Kind.RAN,
        Kind.CONTAINS, Kind.CHANGED));
    kind.setConverter(new StringConverter<>() {
      @Override public String toString(Kind k) {
        return k == null ? "" : I18n.t("lessoneditor.kind." + k.name().toLowerCase());
      }

      @Override public Kind fromString(String s) {
        return null;
      }
    });
    ComboBox<String> file = new ComboBox<>(FXCollections.observableArrayList(javaFiles));
    file.setEditable(true);
    TextArea pieces = area(3);
    pieces.setWrapText(false);
    pieces.getStyleClass().add("lesson-code");
    pieces.setPromptText(I18n.t("lessoneditor.pieces.prompt"));
    CheckBox regex = new CheckBox(I18n.t("lessoneditor.regex"));
    TextField changedPattern = new TextField();
    changedPattern.setPromptText("move\\((\\d+)\\)");
    TextField from = new TextField();
    from.setPromptText("4");
    Label advanced = new Label(I18n.t("lessoneditor.advanced"));
    advanced.setWrapText(true);
    Label result = new Label();
    result.setWrapText(true);
    result.getStyleClass().add("lessoneditor-result");
    Button test = new Button(I18n.t("lessoneditor.test"), Icons.of("fth-check-circle"));

    GridPane checkForm = new GridPane();
    checkForm.setHgap(8);
    checkForm.setVgap(6);
    Label fileLabel = label("lessoneditor.file");
    Label piecesLabel = label("lessoneditor.pieces");
    Label patternLabel = label("lessoneditor.pattern");
    Label fromLabel = label("lessoneditor.from");
    checkForm.addRow(0, fileLabel, file);
    checkForm.addRow(1, piecesLabel, pieces);
    checkForm.add(regex, 1, 2);
    checkForm.addRow(3, patternLabel, changedPattern);
    checkForm.addRow(4, fromLabel, from);
    GridPane.setHgrow(pieces, Priority.ALWAYS);
    GridPane.setHgrow(changedPattern, Priority.ALWAYS);
    file.setMaxWidth(Double.MAX_VALUE);

    GridPane stepForm = new GridPane();
    stepForm.setHgap(8);
    stepForm.setVgap(6);
    stepForm.addRow(0, label("lessoneditor.steptitle"), language("EN"), stepTitleEn);
    stepForm.addRow(1, new Label(), language("DE"), stepTitleDe);
    stepForm.addRow(2, label("lessoneditor.text"), language("EN"), textEn);
    stepForm.addRow(3, new Label(), language("DE"), textDe);
    stepForm.addRow(4, label("lessoneditor.code"), new Label(), code);
    stepForm.addRow(5, label("lessoneditor.done"), new Label(), kind);
    stepForm.add(checkForm, 2, 6);
    stepForm.add(advanced, 2, 7);
    stepForm.add(new HBox(10, test, result), 2, 8);
    GridPane.setHgrow(stepTitleEn, Priority.ALWAYS);
    for (var node : List.of(stepTitleEn, stepTitleDe, textEn, textDe, code)) {
      GridPane.setHgrow(node, Priority.ALWAYS);
    }
    ScrollPane right = new ScrollPane(stepForm);
    right.setFitToWidth(true);
    right.setPrefWidth(560);
    HBox.setHgrow(right, Priority.ALWAYS);

    // keep the form and the selected draft in step
    Draft[] shown = {null};
    Runnable showKind = () -> {
      Kind k = shown[0] == null ? Kind.RAN : shown[0].kind;
      boolean contains = k == Kind.CONTAINS;
      boolean changed = k == Kind.CHANGED;
      for (var node : List.of(piecesLabel, pieces, regex)) visible(node, contains);
      for (var node : List.of(patternLabel, changedPattern, fromLabel, from)) visible(node, changed);
      visible(fileLabel, contains || changed);
      visible(file, contains || changed);
      visible(advanced, k == Kind.ADVANCED);
      kind.setDisable(k == Kind.ADVANCED);
      result.setText("");
    };
    Runnable store = () -> {
      Draft d = shown[0];
      if (d == null) return;
      d.title.put("en", stepTitleEn.getText());
      d.title.put("de", stepTitleDe.getText());
      d.text.put("en", textEn.getText());
      d.text.put("de", textDe.getText());
      d.code = code.getText();
      if (d.kind != Kind.ADVANCED && kind.getValue() != null) d.kind = kind.getValue();
      d.file = file.getEditor().getText() == null ? "" : file.getEditor().getText();
      d.pieces = pieces.getText();
      d.regex = regex.isSelected();
      d.changedPattern = changedPattern.getText();
      d.from = from.getText();
    };
    list.getSelectionModel().selectedItemProperty().addListener((o, old, d) -> {
      store.run();
      shown[0] = d;
      right.setDisable(d == null);
      if (d == null) return;
      stepTitleEn.setText(d.title.getOrDefault("en", ""));
      stepTitleDe.setText(d.title.getOrDefault("de", ""));
      textEn.setText(d.text.getOrDefault("en", ""));
      textDe.setText(d.text.getOrDefault("de", ""));
      code.setText(d.code);
      kind.setValue(d.kind == Kind.ADVANCED ? null : d.kind);
      file.getEditor().setText(d.file.isEmpty() && !javaFiles.isEmpty() ? javaFiles.get(0)
          : d.file);
      pieces.setText(d.pieces);
      regex.setSelected(d.regex);
      changedPattern.setText(d.changedPattern);
      from.setText(d.from);
      showKind.run();
    });
    kind.valueProperty().addListener((o, old, k) -> {
      if (shown[0] != null && k != null && shown[0].kind != Kind.ADVANCED) {
        shown[0].kind = k;
        showKind.run();
      }
    });
    // the list shows the English title as it is typed
    stepTitleEn.textProperty().addListener((o, old, t) -> {
      if (shown[0] != null) {
        shown[0].title.put("en", t);
        list.refresh();
      }
    });
    test.setOnAction(e -> {
      store.run();
      Object step = shown[0] == null ? null : shown[0].toStep();
      if (!(step instanceof Lesson.Step s)) {
        result.setText("✖ " + (step == null ? "" : I18n.t((String) step)));
        return;
      }
      Lesson.Facts now = facts.get();
      if (s.check() instanceof Lesson.Ran) {
        result.setText("ℹ " + I18n.t("lessoneditor.test.ran"));
      } else if (!s.check().met(now)) {
        result.setText("✖ " + I18n.t("lessoneditor.test.notyet"));
      } else if (!now.compiles()) {
        result.setText("✖ " + I18n.t("lessoneditor.test.compile"));
      } else {
        result.setText("✔ " + I18n.t("lessoneditor.test.done"));
      }
    });

    Label error = new Label();
    error.getStyleClass().add("form-error");
    error.setWrapText(true);
    HBox body = new HBox(12, left, right);
    VBox.setVgrow(body, Priority.ALWAYS);
    Label hint = new Label(I18n.t("lessoneditor.hint"));
    hint.setWrapText(true);
    hint.getStyleClass().add("card-button-hint");
    hint.setMinHeight(Region.USE_PREF_SIZE);
    VBox content = new VBox(10, hint, head, body, error);
    content.setPadding(new Insets(4));
    content.setPrefSize(860, 620);
    dialog.getDialogPane().setContent(content);
    list.getSelectionModel().selectFirst();

    // a lesson with a missing piece is not saved: say which step and what
    Lesson[] built = {null};
    dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, e -> {
      store.run();
      if (titleEn.getText().isBlank()) {
        error.setText(I18n.t("lessoneditor.error.lessontitle"));
        e.consume();
        return;
      }
      List<Lesson.Step> steps = new ArrayList<>();
      for (int i = 0; i < drafts.size(); i++) {
        Object step = drafts.get(i).toStep();
        if (step instanceof String key) {
          error.setText(I18n.t("lessoneditor.error.step", i + 1, I18n.t(key)));
          list.getSelectionModel().select(i);
          e.consume();
          return;
        }
        steps.add((Lesson.Step) step);
      }
      Map<String, String> title = new LinkedHashMap<>();
      title.put("en", titleEn.getText().strip());
      if (!titleDe.getText().isBlank()) title.put("de", titleDe.getText().strip());
      built[0] = new Lesson(lesson == null ? newId : lesson.id(),
          lesson == null ? "" : lesson.template(), title, List.copyOf(steps));
    });
    dialog.setResultConverter(button -> button == save ? new Saved(built[0])
        : button == remove ? new Removed() : null);
    return dialog.showAndWait();
  }

  private static String freeId(List<Draft> drafts) {
    for (int n = drafts.size() + 1; ; n++) {
      String id = "step-" + n;
      if (drafts.stream().noneMatch(d -> id.equals(d.id))) return id;
    }
  }

  private static void move(ListView<Draft> list, int by) {
    int at = list.getSelectionModel().getSelectedIndex();
    int to = at + by;
    if (at < 0 || to < 0 || to >= list.getItems().size()) return;
    Draft d = list.getItems().remove(at);
    list.getItems().add(to, d);
    list.getSelectionModel().select(to);
  }

  private static Label label(String key) {
    Label label = new Label(I18n.t(key));
    label.getStyleClass().add("form-label");
    label.setMinWidth(Region.USE_PREF_SIZE);
    GridPane.setValignment(label, javafx.geometry.VPos.TOP);
    label.setAlignment(Pos.TOP_LEFT);
    return label;
  }

  private static Label language(String code) {
    Label label = new Label(code);
    label.setMinWidth(Region.USE_PREF_SIZE);
    label.getStyleClass().add("text-muted");
    return label;
  }

  private static TextArea area(int rows) {
    TextArea area = new TextArea();
    area.setPrefRowCount(rows);
    area.setWrapText(true);
    return area;
  }

  private static void visible(javafx.scene.Node node, boolean on) {
    node.setVisible(on);
    node.setManaged(on);
  }
}
