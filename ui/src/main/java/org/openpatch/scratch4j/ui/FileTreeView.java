package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

/**
 * The project file tree with file-type icons. Generated/build folders are
 * hidden; expansion state survives a refresh. A click opens the file in the
 * matching editor (code, paint, sound).
 */
final class FileTreeView extends TreeView<Path> {

  private static final Set<String> HIDDEN = Set.of(".scratch4j", "target", ".git", "build", "export");

  private Consumer<Path> onOpen = p -> { };
  private Consumer<Path> onRenameClass = p -> { };
  private Runnable onNewClass = () -> { };
  private Consumer<Path> onReveal = folder -> { };
  private Consumer<Path> onRenameAsset = file -> { };
  private Consumer<Path> onDeleteFile = file -> { };
  private Consumer<Path> onEditSprite = file -> { };
  private Consumer<Path> onNewFolder = folder -> { };
  private Consumer<Path> onRenameFolder = folder -> { };
  private Consumer<Path> onDeleteFolder = folder -> { };
  private Path root;

  FileTreeView() {
    setShowRoot(false);
    getStyleClass().add("file-tree");
    setCellFactory(tv -> new PathTreeCell());
    setOnMouseClicked(e -> {
      TreeItem<Path> item = getSelectionModel().getSelectedItem();
      if (item != null && Files.isRegularFile(item.getValue())
          && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
        onOpen.accept(item.getValue());
      }
    });
    setOnKeyPressed(e -> {
      TreeItem<Path> item = getSelectionModel().getSelectedItem();
      if (e.getCode() == javafx.scene.input.KeyCode.ENTER && item != null
          && Files.isRegularFile(item.getValue())) {
        onOpen.accept(item.getValue());
      } else if (e.getCode() == javafx.scene.input.KeyCode.DELETE
          && selectedFile() != null) {
        onDeleteFile.accept(selectedFile());
      } else if (e.getCode() == javafx.scene.input.KeyCode.DELETE
          && selectedDirectory() != null) {
        onDeleteFolder.accept(selectedDirectory());
      }
    });
    MenuItem newClass = new MenuItem(I18n.t("menu.file.newclass"), Icons.of("fth-file-plus"));
    newClass.setOnAction(e -> onNewClass.run());
    MenuItem refresh = new MenuItem(I18n.t("tree.refresh"), Icons.of("fth-refresh-cw"));
    refresh.setOnAction(e -> reload());
    MenuItem newFolder = new MenuItem(I18n.t("folder.new"), Icons.of("fth-folder-plus"));
    newFolder.setOnAction(e -> onNewFolder.accept(folderForCreation()));
    MenuItem renameFolder = new MenuItem(I18n.t("folder.rename"), Icons.of("fth-edit-2"));
    renameFolder.setOnAction(e -> onRenameFolder.accept(selectedDirectory()));
    MenuItem deleteFolder = new MenuItem(I18n.t("folder.delete"), Icons.of("fth-trash-2"));
    deleteFolder.setOnAction(e -> onDeleteFolder.accept(selectedDirectory()));
    MenuItem reveal = new MenuItem(I18n.t("tree.reveal"), Icons.of("fth-folder"));
    reveal.setOnAction(e -> reveal());
    MenuItem renameAsset = new MenuItem(I18n.t("asset.rename"), Icons.of("fth-edit-2"));
    renameAsset.setOnAction(e -> onRenameAsset.accept(selectedAsset()));
    MenuItem deleteFile = new MenuItem(I18n.t("file.delete"), Icons.of("fth-trash-2"));
    deleteFile.setOnAction(e -> onDeleteFile.accept(selectedFile()));
    MenuItem renameClass = new MenuItem(I18n.t("class.rename"), Icons.of("fth-edit-3"));
    renameClass.setOnAction(e -> onRenameClass.accept(selectedFile()));
    MenuItem editSprite = new MenuItem(I18n.t("spriteassets.open.short"),
        Icons.of("fth-image"));
    editSprite.setOnAction(e -> onEditSprite.accept(selectedFile()));
    ContextMenu menu = new ContextMenu(newClass, newFolder, refresh, reveal,
        new javafx.scene.control.SeparatorMenuItem(), editSprite, renameClass, renameAsset,
        renameFolder, deleteFile, deleteFolder);
    menu.setOnShowing(e -> {
      boolean asset = selectedAsset() != null;
      boolean folder = selectedDirectory() != null;
      editSprite.setVisible(VisualMode.isSpriteSource(selectedFile()));
      renameAsset.setVisible(asset);
      Path file = selectedFile();
      renameClass.setVisible(file != null && file.getFileName().toString().endsWith(".java"));
      deleteFile.setVisible(selectedFile() != null);
      renameFolder.setVisible(folder);
      deleteFolder.setVisible(folder);
      newFolder.setVisible(root != null);
    });
    setContextMenu(menu);
  }

  void setOnOpen(Consumer<Path> onOpen) {
    this.onOpen = onOpen;
  }

  /** Opens a folder in the system file manager (the host knows how). */
  void setOnReveal(Consumer<Path> onReveal) {
    this.onReveal = onReveal;
  }

  void setOnNewClass(Runnable onNewClass) {
    this.onNewClass = onNewClass;
  }

  void setOnRenameClass(Consumer<Path> action) {
    onRenameClass = action;
  }

  void setOnRenameAsset(Consumer<Path> action) {
    onRenameAsset = action;
  }

  void setOnDeleteFile(Consumer<Path> action) {
    onDeleteFile = action;
  }

  void setOnEditSprite(Consumer<Path> action) {
    onEditSprite = action;
  }

  void setOnNewFolder(Consumer<Path> action) {
    onNewFolder = action;
  }

  void setOnRenameFolder(Consumer<Path> action) {
    onRenameFolder = action;
  }

  void setOnDeleteFolder(Consumer<Path> action) {
    onDeleteFolder = action;
  }

  Path folderForCreation() {
    TreeItem<Path> item = getSelectionModel().getSelectedItem();
    Path path = item == null ? null : item.getValue();
    if (path == null || root == null) return root;
    return Files.isDirectory(path) ? path : path.getParent();
  }

  Path selectedDirectory() {
    TreeItem<Path> item = getSelectionModel().getSelectedItem();
    Path path = item == null ? null : item.getValue();
    return path != null && root != null && !path.equals(root) && Files.isDirectory(path)
        && path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())
        ? path : null;
  }

  Path selectedFile() {
    TreeItem<Path> item = getSelectionModel().getSelectedItem();
    Path path = item == null ? null : item.getValue();
    return path != null && root != null && Files.isRegularFile(path)
        && path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())
        ? path : null;
  }

  private Path selectedAsset() {
    TreeItem<Path> item = getSelectionModel().getSelectedItem();
    Path path = item == null ? null : item.getValue();
    return path != null && root != null && Files.isRegularFile(path)
        && path.toAbsolutePath().normalize().startsWith(
            root.resolve("assets").toAbsolutePath().normalize()) ? path : null;
  }

  /** Rebuilds the tree for the project at {@code root}. */
  void setRoot(Path root) {
    this.root = root;
    reload();
  }

  /** Rebuilds from disk, keeping which folders were open. */
  void reload() {
    if (root == null) {
      return;
    }
    Set<Path> expanded = new HashSet<>();
    if (getRoot() != null) {
      collectExpanded(getRoot(), expanded);
    }
    setRoot(build(root, expanded, true));
  }

  void selectPath(Path path) {
    TreeItem<Path> item = find(getRoot(), path);
    if (item != null) {
      for (TreeItem<Path> parent = item.getParent(); parent != null;
           parent = parent.getParent()) parent.setExpanded(true);
      getSelectionModel().select(item);
      scrollTo(getRow(item));
    }
  }

  private static TreeItem<Path> find(TreeItem<Path> item, Path path) {
    if (item == null) return null;
    if (item.getValue().equals(path)) return item;
    for (TreeItem<Path> child : item.getChildren()) {
      TreeItem<Path> found = find(child, path);
      if (found != null) return found;
    }
    return null;
  }

  private static void collectExpanded(TreeItem<Path> item, Set<Path> expanded) {
    if (item.isExpanded()) {
      expanded.add(item.getValue());
    }
    for (TreeItem<Path> child : item.getChildren()) {
      collectExpanded(child, expanded);
    }
  }

  private static TreeItem<Path> build(Path dir, Set<Path> expanded, boolean isRoot) {
    TreeItem<Path> item = new TreeItem<>(dir);
    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
    // libraries collapsed by default; everything else open on first show
    item.setExpanded(isRoot || expanded.contains(dir)
        || expanded.isEmpty() && !name.equals("+libs"));
    try (Stream<Path> children = Files.list(dir)) {
      children.filter(p -> !HIDDEN.contains(p.getFileName().toString()))
          .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
              .thenComparing(p -> p.getFileName().toString().toLowerCase()))
          .forEach(p -> item.getChildren().add(Files.isDirectory(p)
              ? build(p, expanded, false) : new TreeItem<>(p)));
    } catch (IOException e) {
      // unreadable folder: show it empty
    }
    return item;
  }

  private void reveal() {
    TreeItem<Path> item = getSelectionModel().getSelectedItem();
    Path target = item == null ? root : item.getValue();
    if (target == null) {
      return;
    }
    // not java.awt.Desktop: initialising AWT from the FX thread deadlocks on Linux
    onReveal.accept(Files.isDirectory(target) ? target : target.getParent());
  }

  private static final class PathTreeCell extends javafx.scene.control.TreeCell<Path> {
    PathTreeCell() {
      setOnMousePressed(e -> {
        if (e.isSecondaryButtonDown() && getTreeItem() != null) {
          getTreeView().getSelectionModel().select(getTreeItem());
        }
      });
      setOnContextMenuRequested(e -> {
        if (getTreeItem() != null) {
          getTreeView().getSelectionModel().select(getTreeItem());
        }
      });
    }

    @Override
    protected void updateItem(Path path, boolean empty) {
      super.updateItem(path, empty);
      if (empty || path == null) {
        setText(null);
        setGraphic(null);
        return;
      }
      setText(path.getFileName().toString());
      var icon = Icons.forFile(path);
      icon.getStyleClass().add("tree-icon");
      setGraphic(icon);
    }
  }
}
