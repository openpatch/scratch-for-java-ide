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

  /** {@code example} is a bundled tutorial/demo id; when set it wins over {@code template}. */
  record Result(Path parentDir, String name, ProjectTemplate template, String example) {}

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
      cards.add(card, i % 2, i / 2);
      if (template == ProjectTemplate.CLASSES_FIRST) {
        card.setSelected(true);
      }
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
    cards.add(exampleCard, i % 2, i / 2);
    cards.add(examples, (i + 1) % 2, i / 2);
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
      }
    });

    TextField nameField = new TextField(freeName("MyGame"));
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
            examples.getValue())
        : null);
    javafx.application.Platform.runLater(() -> {
      nameField.requestFocus();
      nameField.selectAll();
    });
    return dialog.showAndWait();
  }

  private static Result result(Object choice, String name, BundledTemplates.Template example) {
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
