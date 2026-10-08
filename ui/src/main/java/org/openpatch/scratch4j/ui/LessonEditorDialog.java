package org.openpatch.scratch4j.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import javafx.scene.control.Spinner;
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
 * {@code .scratch4j/lesson.json} without touching JSON. The teacher picks the
 * languages the lesson is written in (German only is common), lists the steps
 * on the left and edits the selected one on the right: its title and text in
 * each language, code to copy, and when it is done. "Test with the current
 * code" shows at once whether the step would tick off now.
 */
final class LessonEditorDialog {

  /** What the dialog ended with. */
  sealed interface Outcome {}

  record Saved(Lesson lesson) implements Outcome {}

  record Removed() implements Outcome {}

  /** The conditions the form can edit; ADVANCED is a hand-written one it keeps. */
  enum Kind { RAN, CONTAINS, CHANGED, LINE, CLASS, METHOD, ADVANCED }

  /** The superclasses offered for "a class exists" (any other name can be typed). */
  private static final List<String> BASES = List.of("Sprite", "AnimatedSprite", "UISprite",
      "Stage", "Window");

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
    String line = "";
    String className = "";
    String base = "";
    int min = 1;
    String methodName = "";
    Lesson.Check advanced;

    static Draft of(Lesson.Step step) {
      Draft d = new Draft();
      d.id = step.id();
      d.title.putAll(step.title());
      d.text.putAll(step.text());
      d.code = step.code() == null ? "" : step.code();
      Lesson.Check check = step.check();
      List<Lesson.Contains> contains = containsList(check);
      switch (check) {
        case Lesson.Ran r -> d.kind = Kind.RAN;
        case Lesson.Changed c -> {
          d.kind = Kind.CHANGED;
          d.file = c.file();
          d.changedPattern = c.pattern();
          d.from = c.from();
        }
        case Lesson.LineChanged l -> {
          d.kind = Kind.LINE;
          d.file = l.file();
          d.line = l.line();
        }
        case Lesson.ClassExists c -> {
          d.kind = Kind.CLASS;
          d.className = c.name();
          d.base = c.base();
          d.min = c.min();
        }
        case Lesson.MethodExists m -> {
          d.kind = Kind.METHOD;
          d.methodName = m.name();
          d.file = m.file();
        }
        default -> {
          if (contains != null) {
            d.kind = Kind.CONTAINS;
            d.file = contains.get(0).file();
            d.regex = !contains.get(0).literal();
            d.pieces = String.join("\n",
                contains.stream().map(Lesson.Contains::pattern).toList());
          } else {
            d.kind = Kind.ADVANCED;
            d.advanced = check;
          }
        }
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

    /**
     * The step in these {@code languages}, or the message key of what is
     * missing (a title in one of them, or a part of the condition).
     */
    Object toStep(List<String> languages) {
      for (String language : languages) {
        if (title.getOrDefault(language, "").isBlank()) {
          return "lessoneditor.error.title." + language;
        }
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
          for (String piece : lines) {
            if (regex && !validRegex(piece)) {
              return "lessoneditor.error.regex";
            }
            checks.add(new Lesson.Contains(file.strip(), piece, !regex));
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
        case LINE -> {
          if (file.isBlank() || line.isBlank()) {
            return "lessoneditor.error.line";
          }
          check = new Lesson.LineChanged(file.strip(), line.strip());
        }
        case CLASS -> {
          if (className.isBlank() && base.isBlank()) {
            return "lessoneditor.error.class";
          }
          check = new Lesson.ClassExists(className.strip(), base.strip(), Math.max(1, min));
        }
        case METHOD -> {
          if (!methodName.strip().matches("[A-Za-z_$][\\w$]*")) {
            return "lessoneditor.error.method";
          }
          check = new Lesson.MethodExists(methodName.strip(), file.strip());
        }
        default -> check = advanced;
      }
      return new Lesson.Step(id, Map.copyOf(only(title, languages)),
          Map.copyOf(only(text, languages)), code.isBlank() ? null : code, check);
    }

    /** The texts of these languages, trimmed; texts of languages left out go. */
    private static Map<String, String> only(Map<String, String> texts, List<String> languages) {
      Map<String, String> out = new LinkedHashMap<>();
      for (String language : languages) {
        String value = texts.get(language);
        if (value != null && !value.isBlank()) out.put(language, value.strip());
      }
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
   * files; {@code facts} gives the project as it is now (the test button,
   * and the lines offered for "a line was changed").
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

    // the languages: a new lesson starts in the IDE's own language
    String ideLanguage = I18n.current() == I18n.Language.DE ? "de" : "en";
    List<String> initial = lesson != null ? lesson.languages() : List.of(ideLanguage);
    Map<String, CheckBox> languageBoxes = new LinkedHashMap<>();
    HBox languageRow = new HBox(14, label("lessoneditor.languages"));
    languageRow.setAlignment(Pos.CENTER_LEFT);
    for (String language : Lesson.LANGUAGES) {
      CheckBox box = new CheckBox(I18n.t("lessoneditor.language." + language));
      box.setSelected(initial.contains(language));
      languageBoxes.put(language, box);
      languageRow.getChildren().add(box);
    }
    Supplier<List<String>> languages = () -> Lesson.LANGUAGES.stream()
        .filter(l -> languageBoxes.get(l).isSelected()).toList();

    // the lesson's title, one field per language
    Map<String, TextField> lessonTitle = new LinkedHashMap<>();
    GridPane head = new GridPane();
    head.setHgap(8);
    head.setVgap(6);
    head.add(label("lessoneditor.lessontitle"), 0, 0);
    int column = 1;
    for (String language : Lesson.LANGUAGES) {
      TextField field = new TextField(lesson == null ? ""
          : lesson.title().getOrDefault(language, ""));
      field.setPromptText(I18n.t("lessoneditor.lessontitle.prompt"));
      lessonTitle.put(language, field);
      Label tag = language(language);
      head.add(tag, column++, 0);
      head.add(field, column++, 0);
      GridPane.setHgrow(field, Priority.ALWAYS);
      var shown = languageBoxes.get(language).selectedProperty();
      for (Node node : List.of(tag, field)) {
        node.visibleProperty().bind(shown);
        node.managedProperty().bind(shown);
      }
    }

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
        if (empty || d == null) {
          setText(null);
          return;
        }
        String name = languages.get().stream().map(l -> d.title.getOrDefault(l, "").strip())
            .filter(t -> !t.isEmpty()).findFirst().orElse("…");
        setText((getIndex() + 1) + ". " + name);
      }
    });
    Button add = Icons.button("fth-plus", I18n.t("lessoneditor.add"), () -> {
      Draft d = new Draft();
      d.id = freeId(drafts);
      for (String language : languages.get()) {
        d.title.put(language, I18n.t("lessoneditor.newstep"));
      }
      int at = list.getSelectionModel().getSelectedIndex() + 1;
      drafts.add(at <= 0 ? drafts.size() : at, d);
      list.getSelectionModel().select(d);
    });
    Button delete = Icons.button("fth-trash-2", I18n.t("lessoneditor.delete"), () -> {
      Draft d = list.getSelectionModel().getSelectedItem();
      if (d != null && drafts.size() > 1) drafts.remove(d);
    });
    Button up = Icons.button("fth-arrow-up", I18n.t("lessoneditor.up"), () -> move(list, -1));
    Button down = Icons.button("fth-arrow-down", I18n.t("lessoneditor.down"),
        () -> move(list, 1));
    HBox listButtons = new HBox(4, add, delete, up, down);
    VBox.setVgrow(list, Priority.ALWAYS);
    VBox left = new VBox(6, label("lessoneditor.steps"), list, listButtons);

    // the selected step: a title and a text per language
    GridPane stepForm = new GridPane();
    stepForm.setHgap(8);
    stepForm.setVgap(6);
    Map<String, TextField> stepTitle = new LinkedHashMap<>();
    Map<String, TextArea> stepText = new LinkedHashMap<>();
    Map<String, Label> titleLabels = new LinkedHashMap<>();
    Map<String, Label> textLabels = new LinkedHashMap<>();
    int row = 0;
    for (String language : Lesson.LANGUAGES) {
      TextField field = new TextField();
      stepTitle.put(language, field);
      Label rowLabel = label("lessoneditor.steptitle");
      titleLabels.put(language, rowLabel);
      row = languageRow(stepForm, row, rowLabel, language, field,
          languageBoxes.get(language));
    }
    for (String language : Lesson.LANGUAGES) {
      TextArea field = area(3);
      stepText.put(language, field);
      Label rowLabel = label("lessoneditor.text");
      textLabels.put(language, rowLabel);
      row = languageRow(stepForm, row, rowLabel, language, field,
          languageBoxes.get(language));
    }
    // only the first shown language's row carries the label
    Runnable relabel = () -> {
      List<String> on = languages.get();
      for (String language : Lesson.LANGUAGES) {
        boolean first = !on.isEmpty() && on.get(0).equals(language);
        titleLabels.get(language).setText(first ? I18n.t("lessoneditor.steptitle") : "");
        textLabels.get(language).setText(first ? I18n.t("lessoneditor.text") : "");
      }
    };
    for (CheckBox box : languageBoxes.values()) {
      box.selectedProperty().addListener((o, was, on) -> {
        if (!on && languages.get().isEmpty()) {
          box.setSelected(true); // a lesson needs at least one language
          return;
        }
        relabel.run();
        list.refresh();
      });
    }
    relabel.run();

    TextArea code = area(4);
    code.setWrapText(false);
    code.getStyleClass().add("lesson-code");
    code.setPromptText(I18n.t("lessoneditor.code.prompt"));
    ComboBox<Kind> kind = new ComboBox<>(FXCollections.observableArrayList(Kind.RAN,
        Kind.CONTAINS, Kind.LINE, Kind.CHANGED, Kind.CLASS, Kind.METHOD));
    kind.setConverter(new StringConverter<>() {
      @Override public String toString(Kind k) {
        return k == null ? "" : I18n.t("lessoneditor.kind." + k.name().toLowerCase());
      }

      @Override public Kind fromString(String s) {
        return null;
      }
    });

    // the condition's fields; each kind shows the ones it needs
    ComboBox<String> file = new ComboBox<>(FXCollections.observableArrayList(javaFiles));
    file.setEditable(true);
    file.setMaxWidth(Double.MAX_VALUE);
    TextArea pieces = area(3);
    pieces.setWrapText(false);
    pieces.getStyleClass().add("lesson-code");
    pieces.setPromptText(I18n.t("lessoneditor.pieces.prompt"));
    CheckBox regex = new CheckBox(I18n.t("lessoneditor.regex"));
    TextField changedPattern = new TextField();
    changedPattern.setPromptText("move\\((\\d+)\\)");
    TextField from = new TextField();
    from.setPromptText("4");
    ComboBox<String> line = new ComboBox<>();
    line.setEditable(true);
    line.setMaxWidth(Double.MAX_VALUE);
    line.setPromptText(I18n.t("lessoneditor.line.prompt"));
    line.getStyleClass().add("lesson-code");
    TextField className = new TextField();
    className.setPromptText(I18n.t("lessoneditor.classname.prompt"));
    ComboBox<String> base = new ComboBox<>(FXCollections.observableArrayList(BASES));
    base.setEditable(true);
    base.setPromptText(I18n.t("lessoneditor.base.prompt"));
    base.setMaxWidth(Double.MAX_VALUE);
    Spinner<Integer> min = new Spinner<>(1, 50, 1);
    min.setEditable(true);
    min.setPrefWidth(90);
    TextField methodName = new TextField();
    methodName.setPromptText("whenClicked");
    Label advanced = new Label(I18n.t("lessoneditor.advanced"));
    advanced.setWrapText(true);

    GridPane checkForm = new GridPane();
    checkForm.setHgap(8);
    checkForm.setVgap(6);
    Label fileLabel = label("lessoneditor.file");
    Label piecesLabel = label("lessoneditor.pieces");
    Label patternLabel = label("lessoneditor.pattern");
    Label fromLabel = label("lessoneditor.from");
    Label lineLabel = label("lessoneditor.line");
    Label classLabel = label("lessoneditor.classname");
    Label baseLabel = label("lessoneditor.base");
    Label minLabel = label("lessoneditor.min");
    Label methodLabel = label("lessoneditor.methodname");
    Label fileHint = new Label(I18n.t("lessoneditor.file.any"));
    fileHint.getStyleClass().add("card-button-hint");
    checkForm.addRow(0, classLabel, className);
    checkForm.addRow(1, baseLabel, base);
    checkForm.addRow(2, minLabel, min);
    checkForm.addRow(3, methodLabel, methodName);
    checkForm.addRow(4, fileLabel, file);
    checkForm.add(fileHint, 1, 5);
    checkForm.addRow(6, piecesLabel, pieces);
    checkForm.add(regex, 1, 7);
    checkForm.addRow(8, lineLabel, line);
    checkForm.addRow(9, patternLabel, changedPattern);
    checkForm.addRow(10, fromLabel, from);
    for (Node node : List.of(pieces, changedPattern, line, className, methodName, file)) {
      GridPane.setHgrow(node, Priority.ALWAYS);
    }

    Label result = new Label();
    result.setWrapText(true);
    result.getStyleClass().add("lessoneditor-result");
    Button test = new Button(I18n.t("lessoneditor.test"), Icons.of("fth-check-circle"));

    stepForm.addRow(row++, label("lessoneditor.code"), new Label(), code);
    stepForm.addRow(row++, label("lessoneditor.done"), new Label(), kind);
    stepForm.add(checkForm, 2, row++);
    stepForm.add(advanced, 2, row++);
    stepForm.add(new HBox(10, test, result), 2, row);
    GridPane.setHgrow(code, Priority.ALWAYS);
    ScrollPane right = new ScrollPane(stepForm);
    right.setFitToWidth(true);
    right.setPrefWidth(580);
    HBox.setHgrow(right, Priority.ALWAYS);

    // the lines of the chosen file, for "a line was changed"
    Runnable fillLines = () -> {
      String name = file.getEditor().getText();
      String source = name == null ? null : facts.get().files().get(name.strip());
      List<String> lines = source == null ? List.of() : source.lines().map(String::strip)
          .filter(l -> !l.isEmpty() && !l.startsWith("//") && !l.startsWith("*")
              && !l.startsWith("/*") && !l.equals("{") && !l.equals("}"))
          .distinct().toList();
      String keep = line.getEditor().getText();
      line.getItems().setAll(lines);
      line.getEditor().setText(keep);
    };
    file.getEditor().textProperty().addListener((o, old, now) -> fillLines.run());

    // keep the form and the selected draft in step
    Draft[] shown = {null};
    Runnable showKind = () -> {
      Kind k = shown[0] == null ? Kind.RAN : shown[0].kind;
      visible(k == Kind.CONTAINS, piecesLabel, pieces, regex);
      visible(k == Kind.CHANGED, patternLabel, changedPattern, fromLabel, from);
      visible(k == Kind.LINE, lineLabel, line);
      visible(k == Kind.CLASS, classLabel, className, baseLabel, base, minLabel, min);
      visible(k == Kind.METHOD, methodLabel, methodName, fileHint);
      visible(k == Kind.CONTAINS || k == Kind.CHANGED || k == Kind.LINE || k == Kind.METHOD,
          fileLabel, file);
      visible(k == Kind.ADVANCED, advanced);
      kind.setDisable(k == Kind.ADVANCED);
      result.setText("");
    };
    Runnable store = () -> {
      Draft d = shown[0];
      if (d == null) return;
      for (String language : Lesson.LANGUAGES) {
        d.title.put(language, stepTitle.get(language).getText());
        d.text.put(language, stepText.get(language).getText());
      }
      d.code = code.getText();
      if (d.kind != Kind.ADVANCED && kind.getValue() != null) d.kind = kind.getValue();
      d.file = text(file.getEditor().getText());
      d.pieces = pieces.getText();
      d.regex = regex.isSelected();
      d.changedPattern = changedPattern.getText();
      d.from = from.getText();
      d.line = text(line.getEditor().getText());
      d.className = className.getText();
      d.base = text(base.getEditor().getText());
      d.min = min.getValue() == null ? 1 : min.getValue();
      d.methodName = methodName.getText();
    };
    list.getSelectionModel().selectedItemProperty().addListener((o, old, d) -> {
      store.run();
      shown[0] = d;
      right.setDisable(d == null);
      if (d == null) return;
      for (String language : Lesson.LANGUAGES) {
        stepTitle.get(language).setText(d.title.getOrDefault(language, ""));
        stepText.get(language).setText(d.text.getOrDefault(language, ""));
      }
      code.setText(d.code);
      kind.setValue(d.kind == Kind.ADVANCED ? null : d.kind);
      boolean anyFile = d.kind == Kind.METHOD;
      file.getEditor().setText(d.file.isEmpty() && !anyFile && !javaFiles.isEmpty()
          ? javaFiles.get(0) : d.file);
      pieces.setText(d.pieces);
      regex.setSelected(d.regex);
      changedPattern.setText(d.changedPattern);
      from.setText(d.from);
      line.getEditor().setText(d.line);
      className.setText(d.className);
      base.getEditor().setText(d.base);
      min.getValueFactory().setValue(Math.max(1, d.min));
      methodName.setText(d.methodName);
      showKind.run();
    });
    kind.valueProperty().addListener((o, old, k) -> {
      if (shown[0] != null && k != null && shown[0].kind != Kind.ADVANCED) {
        shown[0].kind = k;
        showKind.run();
      }
    });
    // the list shows the title as it is typed
    for (String language : Lesson.LANGUAGES) {
      stepTitle.get(language).textProperty().addListener((o, old, t) -> {
        if (shown[0] != null) {
          shown[0].title.put(language, t);
          list.refresh();
        }
      });
    }
    test.setOnAction(e -> {
      store.run();
      Object step = shown[0] == null ? null : shown[0].toStep(languages.get());
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
    VBox content = new VBox(10, hint, languageRow, head, body, error);
    content.setPadding(new Insets(4));
    content.setPrefSize(900, 660);
    dialog.getDialogPane().setContent(content);
    list.getSelectionModel().selectFirst();

    // a lesson with a missing piece is not saved: say which step and what
    Lesson[] built = {null};
    dialog.getDialogPane().lookupButton(save).addEventFilter(ActionEvent.ACTION, e -> {
      store.run();
      List<String> on = languages.get();
      Map<String, String> title = new LinkedHashMap<>();
      for (String language : on) {
        String t = lessonTitle.get(language).getText().strip();
        if (t.isEmpty()) {
          error.setText(I18n.t("lessoneditor.error.lessontitle." + language));
          e.consume();
          return;
        }
        title.put(language, t);
      }
      List<Lesson.Step> steps = new ArrayList<>();
      for (int i = 0; i < drafts.size(); i++) {
        Object step = drafts.get(i).toStep(on);
        if (step instanceof String key) {
          error.setText(I18n.t("lessoneditor.error.step", i + 1, I18n.t(key)));
          list.getSelectionModel().select(i);
          e.consume();
          return;
        }
        steps.add((Lesson.Step) step);
      }
      built[0] = new Lesson(lesson == null ? newId : lesson.id(),
          lesson == null ? "" : lesson.template(), on, title, List.copyOf(steps));
    });
    dialog.setResultConverter(button -> button == save ? new Saved(built[0])
        : button == remove ? new Removed() : null);
    return dialog.showAndWait();
  }

  /** One row of the step form for one language, shown while it is selected. */
  private static int languageRow(GridPane grid, int row, Label rowLabel, String language,
      Node field, CheckBox selected) {
    Label tag = language(language);
    grid.addRow(row, rowLabel, tag, field);
    GridPane.setHgrow(field, Priority.ALWAYS);
    for (Node node : List.of(rowLabel, tag, field)) {
      node.visibleProperty().bind(selected.selectedProperty());
      node.managedProperty().bind(selected.selectedProperty());
    }
    return row + 1;
  }

  private static String text(String value) {
    return value == null ? "" : value;
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
    Label label = new Label(code.toUpperCase());
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

  private static void visible(boolean on, Node... nodes) {
    for (Node node : nodes) {
      node.setVisible(on);
      node.setManaged(on);
    }
  }
}
