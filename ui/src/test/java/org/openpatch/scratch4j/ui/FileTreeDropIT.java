package org.openpatch.scratch4j.ui;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeCell;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.openpatch.scratch4j.core.project.LibraryJarSource;

/** Native FILES payloads, as supplied by Windows Explorer (needs a display). */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class FileTreeDropIT {

  @TempDir Path tmp;

  @Test
  void fileManagerDropsImportAssetsWhileTreeDragsStillMove() throws Exception {
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "project",
        LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled")));
    Path project = tmp.resolve("project");
    Files.createDirectory(project.resolve("notes"));
    Path image = Files.write(tmp.resolve("new picture.PNG"), new byte[] {0, 1, 2, -1});
    Path sound = Files.write(tmp.resolve("new sound.mp3"), new byte[] {3, 4, 5, -1});
    Path unsupported = Files.writeString(tmp.resolve("notes.txt"), "notes");
    AtomicReference<List<Path>> payload = new AtomicReference<>(List.of(image, sound));
    AtomicReference<TransferMode> completed = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    AtomicReference<StudioApp> app = new AtomicReference<>();
    AtomicReference<FileTreeView> files = new AtomicReference<>();
    AtomicReference<Label> source = new AtomicReference<>();
    LayoutIT.startFx();
    LayoutIT.onFx(() -> {
      StudioApp studio = new StudioApp();
      Stage stage = new Stage();
      studio.start(stage);
      studio.openProjectAt(project);
      app.set(studio);
      window.set(stage);
    });
    LayoutIT.settle();
    try {
      LayoutIT.onFx(() -> {
        TabPane sidebar = (TabPane) window.get().getScene().lookup(".sidebar-tabs");
        var filesTab = sidebar.getTabs().stream()
            .filter(tab -> tab.getText().equals(I18n.t("files.title"))).findFirst().orElseThrow();
        sidebar.getSelectionModel().select(filesTab);
        VBox content = (VBox) filesTab.getContent();
        files.set((FileTreeView) content.getChildren().stream()
            .filter(FileTreeView.class::isInstance).findFirst().orElseThrow());
        Label fromFileManager = new Label("Files from the system file manager");
        fromFileManager.setMinHeight(40);
        fromFileManager.setOnDragDetected(event -> {
          ClipboardContent clipboard = new ClipboardContent();
          clipboard.putFiles(payload.get().stream().map(Path::toFile).toList());
          fromFileManager.startDragAndDrop(TransferMode.COPY_OR_MOVE).setContent(clipboard);
          event.consume();
        });
        fromFileManager.setOnDragDone(event -> completed.set(event.getTransferMode()));
        content.getChildren().addFirst(fromFileManager);
        source.set(fromFileManager);
      });
      LayoutIT.settle();

      // A file row accepts a mixed selection; images and sounds go to their own folders.
      drag(source.get(), files.get(), project.resolve("Player.java"), false);
      awaitFile(project.resolve("assets/images/new picture.PNG"));
      awaitFile(project.resolve("assets/sounds/new sound.mp3"));
      assertThat(completed.get()).as("Explorer must keep the originals").isEqualTo(TransferMode.COPY);
      assertThat(Files.readAllBytes(project.resolve("assets/images/new picture.PNG")))
          .isEqualTo(Files.readAllBytes(image));
      assertThat(Files.readAllBytes(project.resolve("assets/sounds/new sound.mp3")))
          .isEqualTo(Files.readAllBytes(sound));
      LayoutIT.settle();

      // A folder row and the blank area also import; existing names never get replaced.
      payload.set(List.of(image));
      drag(source.get(), files.get(), project.resolve("assets/images"), false);
      awaitFile(project.resolve("assets/images/new picture2.PNG"));
      LayoutIT.settle();
      payload.set(List.of(sound));
      drag(source.get(), files.get(), null, true);
      awaitFile(project.resolve("assets/sounds/new sound2.mp3"));
      LayoutIT.settle();

      // Unsupported files do not get accepted or copied.
      payload.set(List.of(unsupported));
      completed.set(null);
      drag(source.get(), files.get(), null, true);
      LayoutIT.settle();
      assertThat(completed.get()).isNull();
      assertThat(project.resolve("notes.txt")).doesNotExist();

      // In-window file drags still go to the move callback.
      AtomicReference<List<Path>> moved = new AtomicReference<>();
      LayoutIT.onFx(() -> files.get().setOnMove((path, folder) -> moved.set(List.of(path, folder))));
      AtomicReference<Node> internal = new AtomicReference<>();
      LayoutIT.onFx(() -> internal.set(cell(files.get(), project.resolve("Player.java"))));
      drag(internal.get(), files.get(), project.resolve("notes"), false);
      LayoutIT.settle();
      assertThat(moved.get()).containsExactly(project.resolve("Player.java"), project.resolve("notes"));
      assertThat(image).exists();
      assertThat(sound).exists();
    } finally {
      LayoutIT.onFx(() -> {
        new Robot().mouseRelease(MouseButton.PRIMARY);
        app.get().stopForTests();
        window.get().hide();
      });
    }
  }

  static TreeCell<?> cell(FileTreeView tree, Path path) {
    return tree.lookupAll(".tree-cell").stream().filter(TreeCell.class::isInstance)
        .map(node -> (TreeCell<?>) node).filter(row -> path.equals(row.getItem()))
        .findFirst().orElseThrow();
  }

  static void drag(Node source, FileTreeView tree, Path target, boolean blank) throws Exception {
    AtomicReference<double[]> points = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      Bounds from = source.localToScreen(source.getBoundsInLocal());
      Bounds to = blank ? tree.localToScreen(tree.getBoundsInLocal())
          : cell(tree, target).localToScreen(cell(tree, target).getBoundsInLocal());
      points.set(new double[] {from.getMinX() + 40, from.getCenterY(),
          to.getMinX() + 80, blank ? to.getMaxY() - 30 : to.getCenterY()});
      Robot robot = new Robot();
      robot.mouseMove(points.get()[0], points.get()[1]);
      robot.mousePress(MouseButton.PRIMARY);
    });
    Thread.sleep(150);
    for (int step = 1; step <= 8; step++) {
      double fraction = step / 8.0;
      LayoutIT.onFx(() -> {
        double[] p = points.get();
        new Robot().mouseMove(p[0] + (p[2] - p[0]) * fraction,
            p[1] + (p[3] - p[1]) * fraction);
      });
      Thread.sleep(120);
    }
    LayoutIT.onFx(() -> new Robot().mouseRelease(MouseButton.PRIMARY));
    LayoutIT.settle();
  }

  private static void awaitFile(Path file) throws Exception {
    for (int attempt = 0; attempt < 50 && !Files.isRegularFile(file); attempt++) {
      Thread.sleep(100);
    }
    assertThat(file).isRegularFile();
  }
}
