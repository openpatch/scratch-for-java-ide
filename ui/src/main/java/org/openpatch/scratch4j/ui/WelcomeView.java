package org.openpatch.scratch4j.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import org.openpatch.scratch4j.core.lesson.Lesson;
import org.openpatch.scratch4j.core.lesson.LessonProgress;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The start screen when no project is open: the lesson to continue, create,
 * open, import, recent projects, and a guided lesson to start. Big targets,
 * few words, nothing that leads out of the IDE.
 */
final class WelcomeView extends StackPane {

  private static final double CARD_WIDTH = 240;
  private static final double CARD_GAP = 16;
  /** Width of the text below the cards, kept narrower than the row of cards so it reads well. */
  private static final double TEXT_WIDTH = 600;

  WelcomeView(Runnable onNew, Runnable onOpen, Runnable onImport, Consumer<Path> onOpenRecent) {
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
    subtitle.setMaxWidth(TEXT_WIDTH);

    Button create = bigButton("fth-plus-square", I18n.t("welcome.new"),
        I18n.t("welcome.new.hint"), onNew);
    create.getStyleClass().add("primary-card");
    Button open = bigButton("fth-folder", I18n.t("welcome.open"),
        I18n.t("welcome.open.hint"), onOpen);
    Button importProject = bigButton("fth-archive", I18n.t("welcome.import"),
        I18n.t("welcome.import.hint"), onImport);
    FlowPane actions = new FlowPane(CARD_GAP, CARD_GAP, create, open, importProject);
    Button resume = continueLesson(onOpenRecent);
    if (resume != null) {
      // the unfinished lesson is the main thing to do; New steps back
      create.getStyleClass().remove("primary-card");
      actions.getChildren().add(0, resume);
    }
    actions.setAlignment(Pos.CENTER);
    double cardsWidth = actions.getChildren().size() == 4 ? 2 * CARD_WIDTH + CARD_GAP
        : 3 * CARD_WIDTH + 2 * CARD_GAP;
    actions.setPrefWrapLength(cardsWidth);

    VBox recentBox = new VBox(4);
    recentBox.getStyleClass().add("recent-box");
    recentBox.setMaxWidth(TEXT_WIDTH);
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

    Hyperlink tutorials = new Hyperlink(I18n.t("welcome.lessons"));
    tutorials.setGraphic(Icons.of("fth-compass"));
    tutorials.setOnAction(e -> onNew.run());

    VBox column = new VBox(14, logo, title, subtitle, actions, recentBox, tutorials);
    column.setAlignment(Pos.TOP_CENTER);
    // The padding comes from studio.css, so it is only known once the stylesheet applies.
    column.maxWidthProperty().bind(Bindings.createDoubleBinding(() -> cardsWidth
        + column.getPadding().getLeft() + column.getPadding().getRight(), column.paddingProperty()));
    column.getStyleClass().add("welcome-column");
    ScrollPane scroll = new ScrollPane(new StackPane(column));
    scroll.setFitToWidth(true);
    scroll.setFitToHeight(true);
    scroll.getStyleClass().add("welcome-scroll");
    getChildren().add(scroll);
  }

  /**
   * The most recent project whose lesson is not finished, as a card saying the
   * step; null when there is none.
   */
  private static Button continueLesson(Consumer<Path> onOpen) {
    java.util.Locale locale = I18n.current() == I18n.Language.DE ? java.util.Locale.GERMAN
        : java.util.Locale.ENGLISH;
    for (Path project : Prefs.recentProjects()) {
      try {
        Lesson lesson = Lesson.load(project);
        if (lesson == null) continue;
        LessonProgress progress = LessonProgress.load(lesson, project);
        if (progress.finished()) continue;
        Button card = bigButton("fth-compass", I18n.t("welcome.continue", lesson.title(locale)),
            LessonPath.number(lesson.id()) > 0
                ? I18n.t("welcome.continue.path", progress.current() + 1, lesson.steps().size(),
                    LessonPath.number(lesson.id()), LessonPath.ids().size())
                : I18n.t("welcome.continue.hint", progress.current() + 1, lesson.steps().size(),
                    project.getFileName()), () -> onOpen.accept(project));
        card.setId("welcome-continue");
        card.getStyleClass().add("primary-card");
        return card;
      } catch (java.io.IOException | RuntimeException e) {
        // an unreadable lesson is skipped; the project stays in the recent list
      }
    }
    return null;
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
    button.setPrefSize(CARD_WIDTH, 150);
    button.setOnAction(e -> action.run());
    return button;
  }
}
