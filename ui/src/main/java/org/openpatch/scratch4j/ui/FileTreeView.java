package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

/**
 * The project file tree with file-type icons. Generated/build folders are
 * hidden, and so are folders in {@code assets} with nothing in them (and
 * {@code assets} itself when all of them are empty), so a new project shows
 * only its code; expansion state survives a refresh. A click opens the file in the
 * matching editor (code, paint, sound). Files and folders move by drag and
 * drop or "Move to...", and F2 renames what is selected.
 */
final class FileTreeView extends TreeView<Path> {


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
  private Consumer<Path> onMoveTo = path -> { };
  private Consumer<Path> onDuplicate = file -> { };
  private java.util.function.BiConsumer<Path, Path> onMove = (path, folder) -> { };
  private Path root;
  /** Folders made in the IDE this session: shown even while still empty. */
  private final Set<Path> keepVisible = new HashSet<>();

  /** What a drag inside the tree carries: the dragged file or folder. */
  static final javafx.scene.input.DataFormat PROJECT_PATH =
      new javafx.scene.input.DataFormat("application/x-scratch4j-project-path");

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
      } else if (e.getCode() == javafx.scene.input.KeyCode.F2) {
        renameSelected();
        e.consume();
      }
    });
    // a drop on the empty space below the last row moves into the project folder
    setOnDragOver(e -> {
      Path dragged = draggedPath(e.getDragboard());
      if (dragged != null && root != null && canMoveInto(dragged, root)) {
        e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
      }
      e.consume();
    });
    setOnDragDropped(e -> {
      Path dragged = draggedPath(e.getDragboard());
      boolean done = dragged != null && root != null && canMoveInto(dragged, root);
      if (done) {
        Path target = root;
        javafx.application.Platform.runLater(() -> onMove.accept(dragged, target));
      }
      e.setDropCompleted(done);
      e.consume();
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
    MenuItem deleteFile = new MenuItem(I18n.t("file.delete"), Icons.of("fth-trash-2"));
    deleteFile.setOnAction(e -> onDeleteFile.accept(selectedFile()));
    MenuItem rename = new MenuItem(I18n.t("tree.rename"), Icons.of("fth-edit-2"));
    rename.setAccelerator(new javafx.scene.input.KeyCodeCombination(
        javafx.scene.input.KeyCode.F2));
    rename.setOnAction(e -> renameSelected());
    MenuItem moveTo = new MenuItem(I18n.t("tree.moveto"), Icons.of("fth-corner-up-right"));
    moveTo.setOnAction(e -> onMoveTo.accept(selectedPath()));
    MenuItem duplicate = new MenuItem(I18n.t("tree.duplicate"), Icons.of("fth-copy"));
    duplicate.setOnAction(e -> onDuplicate.accept(selectedFile()));
    MenuItem copyPath = new MenuItem(I18n.t("tree.copypath"), Icons.of("fth-clipboard"));
    copyPath.setOnAction(e -> copyPath());
    MenuItem editSprite = new MenuItem(I18n.t("spriteassets.open.short"),
        Icons.of("fth-image"));
    editSprite.setOnAction(e -> onEditSprite.accept(selectedFile()));
    ContextMenu menu = new ContextMenu(newClass, newFolder, refresh, reveal,
        new javafx.scene.control.SeparatorMenuItem(), editSprite, rename, moveTo, duplicate,
        copyPath, deleteFile, deleteFolder);
    menu.setOnShowing(e -> {
      boolean folder = selectedDirectory() != null;
      boolean file = selectedFile() != null;
      editSprite.setVisible(VisualMode.isSpriteSource(selectedFile()));
      rename.setVisible(file || folder);
      moveTo.setVisible(file || folder);
      duplicate.setVisible(file);
      copyPath.setVisible(file || folder);
      deleteFile.setVisible(file);
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

  /** "Move to...": the host asks for a folder. */
  void setOnMoveTo(Consumer<Path> action) {
    onMoveTo = action;
  }

  /** A drag and drop: the file or folder and the folder it was dropped on. */
  void setOnMove(java.util.function.BiConsumer<Path, Path> action) {
    onMove = action;
  }

  void setOnDuplicate(Consumer<Path> action) {
    onDuplicate = action;
  }

  /** F2: a Java file renames its class, an asset keeps its extension, a folder is a folder. */
  void renameSelected() {
    Path directory = selectedDirectory();
    Path file = selectedFile();
    if (directory != null) {
      onRenameFolder.accept(directory);
    } else if (file != null && file.getFileName().toString().endsWith(".java")) {
      onRenameClass.accept(file);
    } else if (file != null) {
      onRenameAsset.accept(file);
    }
  }

  /** Copies the project-relative path ("assets/images/cat.png"), as code names it. */
  private void copyPath() {
    Path path = selectedPath();
    if (path == null || root == null) return;
    javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
    content.putString(relative(path));
    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
  }

  private String relative(Path path) {
    return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
        .toString().replace('\\', '/');
  }

  /** The selected file or folder (not the project itself). */
  Path selectedPath() {
    Path file = selectedFile();
    return file != null ? file : selectedDirectory();
  }

  /** Whether dropping {@code dragged} on {@code folder} would move it somewhere new. */
  private boolean canMoveInto(Path dragged, Path folder) {
    Path source = dragged.toAbsolutePath().normalize();
    Path target = folder.toAbsolutePath().normalize();
    return Files.isDirectory(target) && !target.equals(source.getParent())
        && !target.startsWith(source);
  }

  private static Path draggedPath(javafx.scene.input.Dragboard board) {
    Object value = board.getContent(PROJECT_PATH);
    return value instanceof String text ? Path.of(text) : null;
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

  /** Rebuilds the tree for the project at {@code root}. */
  void setRoot(Path root) {
    this.root = root;
    reload();
  }

  /** Shows {@code folder} even while it is empty (the student just made it). */
  void keepVisible(Path folder) {
    keepVisible.add(folder.toAbsolutePath().normalize());
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
    TreeItem<Path> built = ProjectTree.build(root, expanded, keepVisible);
    setRoot(built != null ? built : new TreeItem<>(root));
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

  private void reveal() {
    TreeItem<Path> item = getSelectionModel().getSelectedItem();
    Path target = item == null ? root : item.getValue();
    if (target == null) {
      return;
    }
    // not java.awt.Desktop: initialising AWT from the FX thread deadlocks on Linux
    onReveal.accept(Files.isDirectory(target) ? target : target.getParent());
  }

  private final class PathTreeCell extends javafx.scene.control.TreeCell<Path> {
    PathTreeCell() {
      setOnDragDetected(e -> {
        Path path = getItem();
        if (path == null || root == null || path.equals(root)
            || getTreeItem() == null || getTreeItem().getParent() == null) {
          return;
        }
        var board = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.put(PROJECT_PATH, path.toString());
        board.setContent(content);
        e.consume();
      });
      setOnDragOver(e -> {
        Path dragged = draggedPath(e.getDragboard());
        Path folder = dropFolder();
        if (dragged != null && folder != null && canMoveInto(dragged, folder)) {
          e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
        }
        e.consume();
      });
      setOnDragEntered(e -> {
        Path dragged = draggedPath(e.getDragboard());
        Path folder = dropFolder();
        if (dragged != null && folder != null && canMoveInto(dragged, folder)) {
          getStyleClass().add("drop-target");
        }
      });
      setOnDragExited(e -> getStyleClass().removeAll("drop-target"));
      setOnDragDropped(e -> {
        Path dragged = draggedPath(e.getDragboard());
        Path folder = dropFolder();
        boolean done = dragged != null && folder != null && canMoveInto(dragged, folder);
        if (done) {
          // after the drag gesture ends: the host may show a dialog
          javafx.application.Platform.runLater(() -> onMove.accept(dragged, folder));
        }
        e.setDropCompleted(done);
        e.consume();
      });
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

    /** A folder row takes the drop itself; a file row hands it to its folder. */
    private Path dropFolder() {
      Path path = getItem();
      if (path == null || isEmpty()) {
        return root;
      }
      return Files.isDirectory(path) ? path : path.getParent();
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
