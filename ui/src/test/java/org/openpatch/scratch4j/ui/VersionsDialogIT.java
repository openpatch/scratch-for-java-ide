package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.io.ProjectSnapshots;

@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class VersionsDialogIT {

  @TempDir
  Path root;

  @Test
  void includesPendingPaintEditsWhenSavingTheProject() throws Exception {
    Path picture = root.resolve("player.png");
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(8, 8, 2), "png", picture.toFile());
    startFx();
    onFx(() -> {
      EditorTabs editors = new EditorTabs(null, () -> root);
      new javafx.scene.Scene(editors, 900, 600);
      var paint = new ImageEditorView(org.openpatch.scratch4j.core.project.ScratchProject.open(root), picture);
      editors.openTool("image", "Player", "fth-image", () -> paint);
      paint.addLayer();
      paint.image().getPixelWriter().setArgb(1, 1, 0xffff0000);
      assertThat(paint.dirtyProperty().get()).isTrue();
      editors.saveForVersion();
      assertThat(paint.dirtyProperty().get()).isFalse();
      var named = ProjectSnapshots.keep(root, "Painted player");
      var saved = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(named.contents().get("player.png")));
      assertThat(saved.getRGB(1, 1)).isEqualTo(0xffff0000);
    });
  }

  @Test
  @SuppressWarnings("unchecked")
  void savesAllEditsAndFiltersAutomaticVersionsAndRestoresBinaryFiles() throws Exception {
    Path stage = root.resolve("MyStage.java");
    Path picture = root.resolve("player.png");
    Files.writeString(stage, "class MyStage {}");
    Files.write(picture, new byte[] {0, 1, 2});
    ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN);
    AtomicInteger saves = new AtomicInteger();
    AtomicInteger restores = new AtomicInteger();
    AtomicReference<Dialog<ButtonType>> shown = new AtomicReference<>();
    startFx();
    I18n.set(I18n.Language.EN);
    onFx(() -> {
      Dialog<ButtonType> dialog = VersionsDialog.build(root, restores::incrementAndGet, () -> {
        saves.incrementAndGet();
        Files.writeString(stage, "class MyStage { int speed = 5; }");
        Files.write(picture, new byte[] {0, 5, (byte) 255});
      });
      dialog.show();
      shown.set(dialog);
      ((CheckBox) dialog.getDialogPane().lookup("#versions-hide-auto")).setSelected(true);
      ListView<ProjectSnapshots.Version> list = (ListView<ProjectSnapshots.Version>)
          dialog.getDialogPane().lookup("#version-list");
      assertThat(list.getItems()).isEmpty();
      assertThat(dialog.getDialogPane().lookup("#versions-restore-file").isDisabled()).isTrue();
    });
    Platform.runLater(() -> ((Button) shown.get().getDialogPane().lookup("#versions-save")).fire());
    AtomicReference<DialogPane> prompt = new AtomicReference<>();
    for (int i = 0; i < 20 && prompt.get() == null; i++) {
      settle();
      onFx(() -> prompt.set(javafx.stage.Window.getWindows().stream()
          .map(w -> w.getScene() == null ? null : w.getScene().getRoot())
          .filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
          .filter(p -> p != shown.get().getDialogPane() && p.lookup(".text-field") != null)
          .findFirst().orElse(null)));
    }
    assertThat(prompt.get()).as("the version name prompt").isNotNull();
    onFx(() -> {
      ((TextField) prompt.get().lookup(".text-field")).setText("First playable");
      ((Button) prompt.get().lookupButton(ButtonType.OK)).fire();
    });
    settle();
    assertThat(saves.get()).isEqualTo(1);
    var named = ProjectSnapshots.list(root).getFirst();
    assertThat(named.title()).isEqualTo("First playable");
    assertThat(named.files().get("MyStage.java")).contains("speed = 5");
    assertThat(named.contents().get("player.png")).containsExactly(0, 5, (byte) 255);
    Files.write(picture, new byte[] {0, 9, 9});
    onFx(() -> {
      DialogPane pane = shown.get().getDialogPane();
      ListView<ProjectSnapshots.Version> list = (ListView<ProjectSnapshots.Version>) pane.lookup("#version-list");
      assertThat(list.getItems()).extracting(ProjectSnapshots.Version::title).containsExactly("First playable");
      ((CheckBox) pane.lookup("#versions-hide-auto")).setSelected(false);
      assertThat(list.getItems()).hasSize(2);
      assertThat(pane.lookupAll(".label").stream().map(n -> ((javafx.scene.control.Label) n).getText()))
          .anyMatch(s -> s.startsWith("[AUTO]"));
      // Reselect to compare the saved image against the newly changed file.
      list.getSelectionModel().clearSelection();
      list.getSelectionModel().selectFirst();
      ListView<String> files = (ListView<String>) pane.lookup("#versions-files");
      files.getSelectionModel().select("player.png");
      assertThat(((StyleClassedTextArea) pane.lookup("#versions-diff")).getText())
          .isEqualTo(I18n.t("versions.binary"));
      ((Button) pane.lookup("#versions-restore-file")).fire();
      shown.get().close();
    });
    assertThat(restores.get()).isEqualTo(1);
    assertThat(Files.readAllBytes(picture)).containsExactly(0, 5, (byte) 255);
  }
}
