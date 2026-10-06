package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The start screen when no project is open: create, open, recent projects,
 * and a link to the tutorials. Big targets, few words.
 */
final class WelcomeView extends StackPane {

  WelcomeView(Runnable onNew, Runnable onOpen, Consumer<Path> onOpenRecent,
      Consumer<String> onLink) {
    getStyleClass().add("welcome");

    ImageView cat = new ImageView(Branding.idle());
    cat.setViewport(new Rectangle2D(42, 48, 80, 80));
    cat.setFitWidth(160);
    cat.setFitHeight(160);
    cat.setPreserveRatio(true);
    cat.setSmooth(false);
    cat.setOnMouseEntered(e -> cat.setImage(Branding.walk()));
    cat.setOnMouseExited(e -> cat.setImage(Branding.idle()));
    StackPane logo = new StackPane(cat);
    logo.getStyleClass().add("welcome-logo");
    Label title = new Label(I18n.t("welcome.title"));
    title.getStyleClass().add("welcome-title");
    Label subtitle = new Label(I18n.t("welcome.subtitle"));
    subtitle.getStyleClass().add("welcome-subtitle");
    subtitle.setWrapText(true);

    Button create = bigButton("fth-plus-square", I18n.t("welcome.new"),
        I18n.t("welcome.new.hint"), onNew);
    create.getStyleClass().add("primary-card");
    Button open = bigButton("fth-folder", I18n.t("welcome.open"),
        I18n.t("welcome.open.hint"), onOpen);
    HBox actions = new HBox(16, create, open);
    actions.setAlignment(Pos.CENTER);

    VBox recentBox = new VBox(4);
    recentBox.getStyleClass().add("recent-box");
    List<Path> recent = Prefs.recentProjects();
    if (!recent.isEmpty()) {
      Label heading = new Label(I18n.t("welcome.recent"));
      heading.getStyleClass().add("recent-heading");
      recentBox.getChildren().add(heading);
      for (Path project : recent) {
        Button entry = new Button(project.getFileName().toString(), Icons.of("fth-folder"));
        entry.getStyleClass().addAll("flat", "recent-entry");
        Label path = new Label(project.getParent() == null ? "" : project.getParent().toString());
        path.getStyleClass().add("recent-path");
        entry.setOnAction(e -> onOpenRecent.accept(project));
        HBox row = new HBox(8, entry, path);
        row.setAlignment(Pos.CENTER_LEFT);
        recentBox.getChildren().add(row);
      }
    }

    Hyperlink tutorials = new Hyperlink(I18n.t("welcome.tutorials"));
    tutorials.setGraphic(Icons.of("fth-book-open"));
    tutorials.setOnAction(e -> onLink.accept("https://scratch4j.openpatch.org"));

    VBox column = new VBox(14, logo, title, subtitle, actions, recentBox, tutorials);
    column.setAlignment(Pos.TOP_CENTER);
    column.setMaxWidth(640);
    column.getStyleClass().add("welcome-column");
    ScrollPane scroll = new ScrollPane(new StackPane(column));
    scroll.setFitToWidth(true);
    scroll.setFitToHeight(true);
    scroll.getStyleClass().add("welcome-scroll");
    getChildren().add(scroll);
  }

  private static Button bigButton(String icon, String title, String hint, Runnable action) {
    Label titleLabel = new Label(title);
    titleLabel.getStyleClass().add("card-button-title");
    Label hintLabel = new Label(hint);
    hintLabel.getStyleClass().add("card-button-hint");
    hintLabel.setWrapText(true);
    VBox text = new VBox(4, Icons.of(icon, 28), titleLabel, hintLabel);
    text.setAlignment(Pos.CENTER);
    Button button = new Button(null, text);
    button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    button.getStyleClass().add("card-button");
    button.setPrefSize(240, 150);
    button.setOnAction(e -> action.run());
    return button;
  }
}
