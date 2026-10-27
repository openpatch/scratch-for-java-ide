package org.openpatch.scratch4j.ui;

import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.LibraryJarSource;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.settle;

/** The Files tree follows the disk, marks problems, finds by typing, pastes and undoes deletes. */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class FileTreeFeaturesIT {

  @TempDir Path tmp;

  @Test
  void treeFollowsDiskMarksProblemsPastesAndUndoesDeletes() throws Exception {
    String previousSettings = System.getProperty("scratch4j.settingsDir");
    System.setProperty("scratch4j.settingsDir", tmp.resolve("settings").toString());
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "project",
        LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled")));
    Path root = tmp.resolve("project");
    Path broken = Files.writeString(root.resolve("Broken.java"), "class Broken { int x = ; }\n");
    Path notes = Files.createDirectories(root.resolve("notes"));
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
      FileTreeView tree = files.get();

      // a compile error marks its file red
      await(() -> marked(tree, broken, "problem-error"), "Broken.java marked as an error");

      // a file made outside the IDE shows up without Refresh
      Path outside = Files.writeString(notes.resolve("todo.txt"), "outside");
      onFx(() -> tree.selectPath(notes));
      await(() -> shown(tree, outside), "todo.txt appears in the tree");

      // typing jumps to a file, also in a closed folder
      onFx(() -> {
        tree.getSelectionModel().clearSelection();
        for (char c : "tod".toCharArray()) {
          tree.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(c), "",
              KeyCode.UNDEFINED, false, false, false, false));
        }
        assertThat(tree.getSelectionModel().getSelectedItem().getValue()).isEqualTo(outside);
        tree.fireEvent(key(KeyCode.ESCAPE, false));
      });

      // copy and paste into the same folder: a -2 copy
      onFx(() -> {
        tree.selectPath(outside);
        tree.fireEvent(key(KeyCode.C, true));
        tree.fireEvent(key(KeyCode.V, true));
      });
      Path copy = notes.resolve("todo-2.txt");
      await(() -> Files.isRegularFile(copy), "todo-2.txt pasted");

      // delete goes to the trash, Ctrl+Z brings it back
      onFx(() -> {
        tree.selectPath(copy);
        tree.fireEvent(key(KeyCode.DELETE, false));
      });
      DialogPane deletion = awaitDialog("usages.delete.title");
      onFx(() -> button(deletion, "usages.delete.do").fire());
      await(() -> !Files.exists(copy), "todo-2.txt deleted");
      onFx(() -> tree.fireEvent(key(KeyCode.Z, true)));
      await(() -> Files.isRegularFile(copy), "todo-2.txt restored");
      assertThat(Files.readString(copy)).isEqualTo("outside");
    } finally {
      onFx(() -> {
        for (Window shown : new ArrayList<>(Window.getWindows())) {
          if (shown.getScene().getRoot() instanceof DialogPane pane) {
            Button cancel = (Button) pane.lookupButton(I18n.cancel());
            if (cancel != null) cancel.fire();
          }
        }
        app.get().stopForTests();
        window.get().hide();
      });
      if (previousSettings == null) System.clearProperty("scratch4j.settingsDir");
      else System.setProperty("scratch4j.settingsDir", previousSettings);
    }
  }

  private static KeyEvent key(KeyCode code, boolean shortcut) {
    return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut, false, false);
  }

  private static boolean marked(FileTreeView tree, Path path, String pseudoClass) {
    AtomicBoolean result = new AtomicBoolean();
    try {
      onFx(() -> {
        var cell = FileTreeDropIT.cell(tree, path);
        result.set(cell != null
            && cell.getPseudoClassStates().contains(PseudoClass.getPseudoClass(pseudoClass)));
      });
    } catch (Exception e) {
      return false;
    }
    return result.get();
  }

  private static boolean shown(FileTreeView tree, Path path) {
    AtomicBoolean result = new AtomicBoolean();
    try {
      onFx(() -> {
        tree.selectPath(path);
        var item = tree.getSelectionModel().getSelectedItem();
        result.set(item != null && item.getValue().equals(path));
      });
    } catch (Exception e) {
      return false;
    }
    return result.get();
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

  private static Button button(DialogPane pane, String key) {
    return (Button) pane.lookupButton(pane.getButtonTypes().stream()
        .filter(type -> type.getText().equals(I18n.t(key))).findFirst().orElseThrow());
  }

  private static void await(BooleanSupplier condition, String what) throws Exception {
    for (int attempt = 0; attempt < 100 && !condition.getAsBoolean(); attempt++) {
      Thread.sleep(100);
    }
    assertThat(condition.getAsBoolean()).as(what).isTrue();
  }
}
