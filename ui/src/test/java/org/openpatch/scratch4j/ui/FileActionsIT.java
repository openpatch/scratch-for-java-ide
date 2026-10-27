package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.FileUsages;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;
import org.openpatch.scratch4j.core.project.LibraryJarSource;

/** Real mouse clicks on owned move/delete dialogs, including on Hyprland/XWayland. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class FileActionsIT {

  @TempDir Path tmp;

  @Test
  void movesFromMenuAndDragThenWarnsBeforeDeletingReferencedFiles() throws Exception {
    I18n.Language previousLanguage = I18n.defaultLanguage();
    String previousSettings = System.getProperty("scratch4j.settingsDir");
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    I18n.set(I18n.Language.DE);
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "project",
        LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled")));
    Path root = tmp.resolve("project");
    Path image = root.resolve("assets/images/hero.png");
    Path spare = root.resolve("assets/images/spare.png");
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", image.toFile());
    Files.copy(image, spare);
    Path folder = Files.createDirectories(root.resolve("assets/images/other"));
    Files.writeString(folder.resolve("readme.txt"), "keep folder visible");
    Path code = Files.writeString(root.resolve("AssetReferences.java"),
        "class AssetReferences { String image = \"assets/images/hero.png\"; }\n");
    AtomicReference<Stage> window = new AtomicReference<>();
    AtomicReference<StudioApp> app = new AtomicReference<>();
    AtomicReference<FileTreeView> files = new AtomicReference<>();
    LayoutIT.startFx();
    onFx(() -> {
      StudioApp studio = new StudioApp();
      Stage stage = new Stage();
      studio.start(stage);
      studio.openProjectAt(root);
      window.set(stage);
      app.set(studio);
    });
    settle();
    try {
      onFx(() -> {
        TabPane sidebar = (TabPane) window.get().getScene().lookup(".sidebar-tabs");
        var tab = sidebar.getTabs().stream()
            .filter(item -> item.getText().equals(I18n.t("files.title"))).findFirst().orElseThrow();
        sidebar.getSelectionModel().select(tab);
        files.set((FileTreeView) ((VBox) tab.getContent()).getChildren().stream()
            .filter(FileTreeView.class::isInstance).findFirst().orElseThrow());
      });

      action(files.get(), image, "tree.moveto");
      DialogPane chooser = awaitDialog("tree.moveto");
      assertOwned(chooser, window.get());
      onFx(() -> {
        @SuppressWarnings("unchecked")
        ComboBox<String> destinations = (ComboBox<String>) chooser.lookup(".combo-box");
        destinations.setValue("assets/images/other/");
      });
      click(button(chooser, "button.ok"));
      DialogPane preview = awaitDialog("usages.move.title");
      assertOwned(preview, window.get());
      assertReferences(preview, code);
      click(button(preview, "usages.move.do"));
      await(() -> Files.isRegularFile(folder.resolve("hero.png")));
      assertThat(Files.readString(code)).contains("assets/images/other/hero.png");
      assertThat(image).doesNotExist();
      settle();

      // A drag goes through the same preview; the native button must work here too.
      AtomicReference<javafx.scene.Node> source = new AtomicReference<>();
      onFx(() -> {
        files.get().selectPath(folder.resolve("hero.png"));
        source.set(FileTreeDropIT.cell(files.get(), folder.resolve("hero.png")));
      });
      FileTreeDropIT.drag(source.get(), files.get(), image.getParent(), false);
      preview = awaitDialog("usages.move.title");
      assertOwned(preview, window.get());
      click(button(preview, "usages.move.do"));
      await(() -> Files.isRegularFile(image));
      assertThat(Files.readString(code)).contains("assets/images/hero.png")
          .doesNotContain("assets/images/other/hero.png");
      settle();

      // The same path is previewed before deletion, with a warning for the remaining reference.
      action(files.get(), image, "file.delete");
      DialogPane deletion = awaitDialog("usages.delete.title");
      assertOwned(deletion, window.get());
      assertReferences(deletion, code);
      DialogPane warning = deletion;
      onFx(() -> {
        assertThat(warning.getStyleClass()).contains("warning");
        assertThat(button(warning, "button.cancel").isDefaultButton()).isTrue();
        assertThat(warning.lookupAll(".usage-manual")).isNotEmpty();
        assertThat(warning.lookupAll(".usage-updatable")).isEmpty();
      });
      click(button(deletion, "button.cancel"));
      assertThat(image).isRegularFile();

      action(files.get(), image, "file.delete");
      deletion = awaitDialog("usages.delete.title");
      click(button(deletion, "usages.delete.anyway"));
      await(() -> !Files.exists(image));
      assertThat(Files.readString(code)).contains("assets/images/hero.png");
      settle();

      action(files.get(), spare, "file.delete");
      deletion = awaitDialog("usages.delete.title");
      DialogPane unused = deletion;
      onFx(() -> {
        assertThat(unused.getStyleClass()).contains("confirmation").doesNotContain("warning");
        assertThat(unused.lookup(".list-view")).isNull();
        assertThat(unused.lookupAll(".label").stream().map(node -> ((Label) node).getText()))
            .contains("assets/images/spare.png", I18n.t("usages.delete.unused"));
      });
      click(button(deletion, "usages.delete.do"));
      await(() -> !Files.exists(spare));
      try (var trash = Files.list(root.resolve(".scratch4j/trash"))) {
        assertThat(trash.map(path -> path.getFileName().toString()))
            .anyMatch(name -> name.endsWith("-hero.png"))
            .anyMatch(name -> name.endsWith("-spare.png"));
      }
    } finally {
      onFx(() -> {
        new Robot().mouseRelease(MouseButton.PRIMARY);
        for (Window shown : new ArrayList<>(Window.getWindows())) {
          if (shown.getScene().getRoot() instanceof DialogPane pane) {
            Button cancel = (Button) pane.lookupButton(I18n.cancel());
            if (cancel != null) cancel.fire();
          }
        }
        app.get().stopForTests();
        window.get().hide();
      });
      I18n.set(previousLanguage);
      if (previousSettings == null) System.clearProperty("scratch4j.settingsDir");
      else System.setProperty("scratch4j.settingsDir", previousSettings);
    }
  }

  private static void action(FileTreeView tree, Path path, String key) throws Exception {
    onFx(() -> {
      tree.selectPath(path);
      var menu = tree.getContextMenu().getItems().stream()
          .filter(item -> I18n.t(key).equals(item.getText())).findFirst().orElseThrow();
      Platform.runLater(menu::fire);
    });
  }

  private static DialogPane awaitDialog(String title) throws Exception {
    AtomicReference<DialogPane> result = new AtomicReference<>();
    for (int attempt = 0; attempt < 60 && result.get() == null; attempt++) {
      onFx(() -> Window.getWindows().stream().filter(Stage.class::isInstance)
          .map(Stage.class::cast).filter(stage -> I18n.t(title).equals(stage.getTitle()))
          .filter(stage -> stage.getScene().getRoot() instanceof DialogPane)
          .findFirst().ifPresent(stage -> result.set((DialogPane) stage.getScene().getRoot())));
      Thread.sleep(100);
    }
    assertThat(result.get()).as("dialog %s", I18n.t(title)).isNotNull();
    settle();
    return result.get();
  }

  private static void assertOwned(DialogPane pane, Stage owner) throws Exception {
    onFx(() -> {
      Stage dialog = (Stage) pane.getScene().getWindow();
      assertThat(dialog.getOwner()).as("native transient parent").isSameAs(owner);
      assertThat(dialog.getModality()).isEqualTo(Modality.WINDOW_MODAL);
    });
  }

  private static void assertReferences(DialogPane pane, Path file) throws Exception {
    onFx(() -> {
      ListView<?> references = (ListView<?>) pane.lookup(".list-view");
      assertThat(references.getItems()).hasSize(1);
      assertThat(((FileUsages.Usage) references.getItems().getFirst()).file()).isEqualTo(file);
    });
  }

  private static Button button(DialogPane pane, String key) {
    return (Button) pane.lookupButton(pane.getButtonTypes().stream()
        .filter(type -> type.getText().equals(I18n.t(key))).findFirst().orElseThrow());
  }

  private static void click(Button button) throws Exception {
    AtomicInteger actions = new AtomicInteger();
    onFx(() -> {
      button.addEventHandler(javafx.event.ActionEvent.ACTION, event -> actions.incrementAndGet());
      Bounds bounds = button.localToScreen(button.getBoundsInLocal());
      new Robot().mouseMove(bounds.getCenterX(), bounds.getCenterY());
    });
    Thread.sleep(100);
    onFx(() -> new Robot().mousePress(MouseButton.PRIMARY));
    Thread.sleep(100);
    onFx(() -> new Robot().mouseRelease(MouseButton.PRIMARY));
    settle();
    onFx(() -> assertThat(actions.get()).as("native click on %s", button.getText()).isEqualTo(1));
  }

  private static void await(BooleanSupplier condition) throws Exception {
    for (int attempt = 0; attempt < 50 && !condition.getAsBoolean(); attempt++) {
      Thread.sleep(100);
    }
    assertThat(condition.getAsBoolean()).as("operation completed after clicking its button").isTrue();
  }
}
