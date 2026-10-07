package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.openpatch.scratch4j.core.project.Abiturklassen;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which NRW Abiturklassen to put into the project: grouped like the QUA-LiS
 * download, only {@code List} ticked (the one Scratch for Java NRW uses).
 * Classes a ticked one needs come along and are named below the list.
 */
final class AbiturklassenDialog {

  /** The ticked classes; {@code source} is a zip the user picked, or null to download. */
  record Choice(Set<String> classes, Path source) {}

  private AbiturklassenDialog() {}

  static Optional<Choice> show(Set<String> present) {
    return build(present).showAndWait();
  }

  /** The dialog, not shown yet (tests show it without waiting). */
  static Dialog<Choice> build(Set<String> present) {
    Dialog<Choice> dialog = new Dialog<>();
    dialog.setTitle(I18n.t("abitur.title"));
    Theme.style(dialog);
    ButtonType importType = new ButtonType(I18n.t("abitur.import"),
        ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(importType, I18n.cancel());

    Label intro = new Label(I18n.t("abitur.intro"));
    intro.setWrapText(true);
    VBox content = new VBox(10, intro);

    Map<String, CheckBox> boxes = new LinkedHashMap<>();
    Map<String, VBox> groups = new LinkedHashMap<>();
    for (Abiturklassen.Entry entry : Abiturklassen.ENTRIES) {
      VBox group = groups.computeIfAbsent(entry.group(), g -> {
        Label title = new Label(I18n.t("abitur.group." + g));
        title.getStyleClass().add("form-label");
        VBox box = new VBox(4, title);
        content.getChildren().add(box);
        return box;
      });
      String name = entry.className();
      boolean there = present.contains(name);
      CheckBox box = new CheckBox(name + "  –  " + I18n.t("abitur.class." + name)
          + (there ? "  (" + I18n.t("abitur.present") + ")" : ""));
      box.setSelected(there || name.equals(Abiturklassen.LIST));
      box.setDisable(there);
      boxes.put(name, box);
      group.getChildren().add(box);
    }
    Label database = new Label(I18n.t("abitur.database.note"));
    database.setWrapText(true);
    database.getStyleClass().add("form-hint");
    groups.get("database").getChildren().add(database);

    Label along = new Label();
    along.setWrapText(true);
    along.getStyleClass().add("form-hint");

    Path[] source = {null};
    Label from = new Label(I18n.t("abitur.source.download"));
    from.setWrapText(true);
    from.getStyleClass().add("form-hint");
    Button pick = new Button(I18n.t("abitur.source.pick"), Icons.of("fth-folder"));
    pick.setOnAction(e -> {
      FileChooser chooser = new FileChooser();
      chooser.setTitle(I18n.t("abitur.source.pick"));
      chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
      File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
      if (file != null) {
        source[0] = file.toPath();
        from.setText(I18n.t("abitur.source.file", file.getName()));
      }
    });
    pick.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    HBox sourceRow = new HBox(8, pick, from);
    sourceRow.setAlignment(Pos.CENTER_LEFT);

    Runnable update = () -> {
      Set<String> ticked = ticked(boxes, present);
      List<String> extra = new ArrayList<>(Abiturklassen.withRequirements(ticked));
      extra.removeAll(ticked);
      extra.removeAll(present);
      along.setText(extra.isEmpty() ? "" : I18n.t("abitur.along", String.join(", ", extra)));
      dialog.getDialogPane().lookupButton(importType).setDisable(ticked.isEmpty());
    };
    boxes.values().forEach(b -> b.selectedProperty().addListener((o, was, now) -> update.run()));
    update.run();

    ScrollPane scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(420);
    content.setPadding(new Insets(0, 8, 0, 0));
    VBox pane = new VBox(10, scroll, along, sourceRow);
    pane.setPrefWidth(540);
    dialog.getDialogPane().setContent(pane);
    dialog.setResultConverter(bt -> bt == importType
        ? new Choice(ticked(boxes, present), source[0]) : null);
    dialog.getDialogPane().getProperties().put("boxes", boxes);
    dialog.getDialogPane().getProperties().put("along", along);
    return dialog;
  }

  /** Ticked and not in the project yet. */
  private static Set<String> ticked(Map<String, CheckBox> boxes, Set<String> present) {
    Set<String> ticked = new LinkedHashSet<>();
    boxes.forEach((name, box) -> {
      if (box.isSelected() && !present.contains(name)) ticked.add(name);
    });
    return ticked;
  }
}
