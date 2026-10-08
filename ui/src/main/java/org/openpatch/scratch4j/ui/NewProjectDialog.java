package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.ProjectTemplate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The new-project wizard: pick a template card, a name and a folder. The
 * project is then created with the bundled library jar in {@code +libs}.
 */
final class NewProjectDialog {

  /**
   * {@code example} is a bundled tutorial/demo id; when set it wins over
   * {@code template}. {@code lesson} is a bundled lesson id: its template plus
   * the lesson; it wins over both.
   */
  record Result(Path parentDir, String name, ProjectTemplate template, String example,
      String lesson) {

    Result(Path parentDir, String name, ProjectTemplate template, String example) {
      this(parentDir, name, template, example, null);
    }
  }

  private static final String LESSON = "lesson:";

  private static final String EXAMPLE = "example";

  private static Path lastDir = Path.of(System.getProperty("user.home"));

  private NewProjectDialog() {}

  static Optional<Result> show(Window owner) {
    Dialog<Result> dialog = new Dialog<>();
    dialog.initOwner(owner);
    dialog.setTitle(I18n.t("wizard.title"));
    Theme.style(dialog);
    ButtonType createType = new ButtonType(I18n.t("wizard.create"), ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(createType, I18n.cancel());

    ToggleGroup templates = new ToggleGroup();
    GridPane cards = new GridPane();
    cards.setHgap(10);
    cards.setVgap(10);
    // first and preselected: a guided lesson, for a student's first projects
    java.util.Locale locale = I18n.current() == I18n.Language.DE ? java.util.Locale.GERMAN
        : java.util.Locale.ENGLISH;
    ComboBox<org.openpatch.scratch4j.core.lesson.Lesson> lessons = new ComboBox<>();
    for (String id : org.openpatch.scratch4j.core.lesson.Lesson.bundledIds()) {
      try {
        lessons.getItems().add(org.openpatch.scratch4j.core.lesson.Lesson.bundled(id));
      } catch (java.io.IOException ignored) {
        // a broken bundled lesson is left out; the tests keep them working
      }
    }
    lessons.getSelectionModel().selectFirst();
    lessons.setMaxWidth(Double.MAX_VALUE);
    lessons.setConverter(new StringConverter<>() {
      @Override
      public String toString(org.openpatch.scratch4j.core.lesson.Lesson lesson) {
        return lesson == null ? "" : I18n.t("template.lesson.item", lesson.title(locale),
            lesson.steps().size());
      }

      @Override
      public org.openpatch.scratch4j.core.lesson.Lesson fromString(String s) {
        return null;
      }
    });
    Label lessonTitle = new Label(I18n.t("template.lesson"));
    lessonTitle.getStyleClass().add("card-button-title");
    Label lessonHint = new Label(I18n.t("template.lesson.hint"));
    lessonHint.getStyleClass().add("card-button-hint");
    lessonHint.setWrapText(true);
    VBox lessonContent = new VBox(4, Icons.of("fth-compass", 22), lessonTitle, lessonHint);
    ToggleButton lessonCard = new ToggleButton(null, lessonContent);
    lessonCard.getStyleClass().add("template-card");
    lessonCard.setToggleGroup(templates);
    lessonCard.setUserData(LESSON);
    lessonCard.setPrefSize(250, 118);
    lessonCard.setMaxWidth(Double.MAX_VALUE);
    lessonCard.setSelected(!lessons.getItems().isEmpty());
    lessonCard.setDisable(lessons.getItems().isEmpty());
    cards.add(lessonCard, 0, 0);
    cards.add(lessons, 1, 0);
    GridPane.setValignment(lessons, javafx.geometry.VPos.CENTER);
    lessons.disableProperty().bind(lessonCard.selectedProperty().not());
    // the project is named after the lesson until the student types a name
    String[] autoName = {""};
    Runnable nameAfterLesson = () -> {
      var lesson = lessons.getValue();
      TextField field = nameField(dialog);
      if (lesson != null && field != null) {
        autoName[0] = freeName(projectName(lesson.title(locale)));
        field.setText(autoName[0]);
      }
    };
    lessons.setOnAction(e -> nameAfterLesson.run());
    lessonCard.setOnAction(e -> nameAfterLesson.run());
    int i = 0;
    for (ProjectTemplate template : ProjectTemplate.values()) {
      String key = "template." + template.name().toLowerCase(Locale.ROOT);
      Label title = new Label(I18n.t(key));
      title.getStyleClass().add("card-button-title");
      Label hint = new Label(I18n.t(key + ".hint"));
      hint.getStyleClass().add("card-button-hint");
      hint.setWrapText(true);
      VBox content = new VBox(4, Icons.of(iconOf(template), 22), title, hint);
      content.setAlignment(Pos.TOP_LEFT);
      ToggleButton card = new ToggleButton(null, content);
      card.getStyleClass().add("template-card");
      card.setToggleGroup(templates);
      card.setUserData(template);
      card.setPrefSize(250, 118);
      card.setMaxWidth(Double.MAX_VALUE);
      cards.add(card, i % 2, i / 2 + 1);
      i++;
    }
    // the fifth card: a finished tutorial project or a library demo (bundled, offline)
    ComboBox<BundledTemplates.Template> examples = new ComboBox<>();
    examples.getItems().setAll(BundledTemplates.list());
    examples.getSelectionModel().selectFirst();
    examples.setMaxWidth(Double.MAX_VALUE);
    examples.setConverter(new StringConverter<>() {
      @Override
      public String toString(BundledTemplates.Template t) {
        return t == null ? "" : I18n.t(t.isTutorial() ? "template.example.tutorial"
            : "template.example.demo", t.title());
      }

      @Override
      public BundledTemplates.Template fromString(String s) {
        return null;
      }
    });
    Label exampleTitle = new Label(I18n.t("template.example"));
    exampleTitle.getStyleClass().add("card-button-title");
    Label exampleHint = new Label(I18n.t("template.example.hint"));
    exampleHint.getStyleClass().add("card-button-hint");
    exampleHint.setWrapText(true);
    VBox exampleContent = new VBox(4, Icons.of("fth-book-open", 22), exampleTitle, exampleHint);
    ToggleButton exampleCard = new ToggleButton(null, exampleContent);
    exampleCard.getStyleClass().add("template-card");
    exampleCard.setToggleGroup(templates);
    exampleCard.setUserData(EXAMPLE);
    exampleCard.setPrefSize(250, 118);
    exampleCard.setMaxWidth(Double.MAX_VALUE);
    cards.add(exampleCard, i % 2, i / 2 + 1);
    cards.add(examples, (i + 1) % 2, i / 2 + 1);
    GridPane.setValignment(examples, javafx.geometry.VPos.CENTER);
    examples.disableProperty().bind(exampleCard.selectedProperty().not());
    exampleCard.setOnAction(e -> examples.getOnAction().handle(null));
    examples.setOnAction(e -> {
      BundledTemplates.Template t = examples.getValue();
      if (t != null && nameField(dialog) != null) {
        nameField(dialog).setText(freeName(t.title().replaceAll("[^\\w\\- ]", "")
            .replace(' ', '-')));
      }
    });

    templates.selectedToggleProperty().addListener((o, old, toggle) -> {
      if (toggle == null) {
        old.setSelected(true);
      } else if (!LESSON.equals(toggle.getUserData()) && nameField(dialog) != null
          && nameField(dialog).getText().equals(autoName[0])) {
        nameField(dialog).setText(freeName("MyGame"));
      }
    });

    autoName[0] = lessons.getValue() == null ? freeName("MyGame")
        : freeName(projectName(lessons.getValue().title(locale)));
    TextField nameField = new TextField(autoName[0]);
    nameField.setId("project-name");
    TextField folderField = new TextField(lastDir.toAbsolutePath().toString());
    folderField.setEditable(false);
    HBox.setHgrow(folderField, Priority.ALWAYS);
    Button choose = new Button(I18n.t("wizard.folder.choose"), Icons.of("fth-folder"));
    choose.setOnAction(e -> {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setInitialDirectory(lastDir.toFile());
      chooser.setTitle(I18n.t("wizard.folder.choose"));
      File dir = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
      if (dir != null) {
        lastDir = dir.toPath();
        folderField.setText(dir.getAbsolutePath());
      }
    });

    Label error = new Label();
    error.getStyleClass().add("form-error");
    Runnable validate = () -> {
      String name = nameField.getText().trim();
      String message = name.isEmpty() || !name.matches("[\\w\\- ]+")
          ? I18n.t("wizard.error.name")
          : Files.exists(lastDir.resolve(name)) ? I18n.t("wizard.error.exists") : "";
      error.setText(message);
      dialog.getDialogPane().lookupButton(createType).setDisable(!message.isEmpty());
    };
    nameField.textProperty().addListener((o, old, v) -> validate.run());
    folderField.textProperty().addListener((o, old, v) -> validate.run());
    validate.run();

    VBox form = new VBox(8,
        sectionLabel(I18n.t("wizard.template")), cards,
        sectionLabel(I18n.t("wizard.name")), nameField,
        sectionLabel(I18n.t("wizard.folder")), new HBox(8, folderField, choose),
        error);
    form.setPrefWidth(540);
    dialog.getDialogPane().setContent(form);
    dialog.setResultConverter(bt -> bt == createType
        ? result(templates.getSelectedToggle().getUserData(), nameField.getText().trim(),
            examples.getValue(), lessons.getValue())
        : null);
    javafx.application.Platform.runLater(() -> {
      nameField.requestFocus();
      nameField.selectAll();
    });
    return dialog.showAndWait();
  }

  private static Result result(Object choice, String name, BundledTemplates.Template example,
      org.openpatch.scratch4j.core.lesson.Lesson lesson) {
    if (LESSON.equals(choice) && lesson != null) {
      return new Result(lastDir, name, ProjectTemplate.CLASSES_FIRST, null, lesson.id());
    }
    if (EXAMPLE.equals(choice)) {
      return new Result(lastDir, name, ProjectTemplate.CLASSES_FIRST,
          example == null ? null : example.id());
    }
    return new Result(lastDir, name, (ProjectTemplate) choice, null);
  }

  private static TextField nameField(Dialog<?> dialog) {
    return (TextField) dialog.getDialogPane().lookup("#project-name");
  }

  private static String iconOf(ProjectTemplate template) {
    return switch (template) {
      case IMPERATIVE -> "fth-file-text";
      case CLASSES_FIRST -> "fth-layers";
      case BLUEJ_STARTER -> "fth-box";
      case VSCODE_STARTER -> "fth-code";
    };
  }

  private static Label sectionLabel(String text) {
    Label label = new Label(text);
    label.getStyleClass().add("form-label");
    return label;
  }

  /** A folder name from a lesson title: "Fang die Münzen" becomes "Fang-die-Muenzen". */
  static String projectName(String title) {
    String name = title.replace("\u00e4", "ae").replace("\u00f6", "oe").replace("\u00fc", "ue")
        .replace("\u00c4", "Ae").replace("\u00d6", "Oe").replace("\u00dc", "Ue")
        .replace("\u00df", "ss").replaceAll("[^A-Za-z0-9\\- ]", "").strip()
        .replaceAll("\\s+", "-");
    return name.isEmpty() ? "MyGame" : name;
  }

  private static String freeName(String base) {
    String name = base;
    int n = 2;
    while (Files.exists(lastDir.resolve(name))) {
      name = base + n++;
    }
    return name;
  }

  static void showError(String message) {
    Alert alert = new Alert(Alert.AlertType.ERROR,
        message == null ? I18n.t("wizard.error.exists") : message, I18n.ok());
    alert.setHeaderText(null);
    alert.showAndWait();
  }
}
