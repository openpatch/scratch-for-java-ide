package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.Dragboard;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import org.openpatch.scratch4j.core.assets.AssetCopier;

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
  private Consumer<List<Path>> onImport = files -> { };
  private Path root;
  /** In-window drags avoid a native GTK mouse grab that can outlive the drop on XWayland. */
  private Path dragging;
  /** Read a native file drag only once, rather than on every mouse move. */
  private Dragboard externalDragboard;
  private List<Path> importing = List.of();
  /** Folders made in the IDE this session: shown even while still empty. */
  private final Set<Path> keepVisible = new HashSet<>();

  FileTreeView() {
    setShowRoot(false);
    getStyleClass().add("file-tree");
    setCellFactory(tv -> new PathTreeCell());
    setOnMouseClicked(e -> {
      TreeItem<Path> item = getSelectionModel().getSelectedItem();
      if (item != null && Files.isRegularFile(item.getValue())
          && e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.isStillSincePress()) {
        onOpen.accept(item.getValue());
      }
    });
    setOnKeyPressed(e -> {
      TreeItem<Path> item = getSelectionModel().getSelectedItem();
      if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && dragging != null) {
        clearInternalDrag();
        e.consume();
      } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER && item != null
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
    // Empty space moves internal files into the root, or imports external assets.
    setOnDragOver(e -> {
      TransferMode mode = dropMode(e.getDragboard(), root);
      if (mode != null) e.acceptTransferModes(mode);
      e.consume();
    });
    setOnDragDropped(e -> drop(e, root));
    setOnDragExited(e -> clearExternalDrag());
    setOnMouseDragOver(e -> {
      setCursor(canDropMove(root) ? javafx.scene.Cursor.MOVE : javafx.scene.Cursor.DEFAULT);
      e.consume();
    });
    setOnMouseDragReleased(e -> {
      dropMove(root);
      e.consume();
    });
    setOnMouseReleased(e -> javafx.application.Platform.runLater(this::clearInternalDrag));
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

  /** Files from the system file manager are copied into the project's asset folders. */
  void setOnImport(Consumer<List<Path>> action) {
    onImport = action;
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

  /**
   * Whether dropping {@code dragged} on {@code folder} (a folder of the tree)
   * would move it somewhere new. No disk access: it runs on every mouse move.
   */
  private static boolean canMoveInto(Path dragged, Path folder) {
    Path source = dragged.toAbsolutePath().normalize();
    Path target = folder.toAbsolutePath().normalize();
    return !target.equals(source.getParent()) && !target.startsWith(source);
  }

  private TransferMode dropMode(Dragboard board, Path folder) {
    if (root == null || folder == null) return null;
    if (externalDragboard != board) {
      externalDragboard = board;
      importing = board.hasFiles()
          ? board.getFiles().stream().map(java.io.File::toPath).toList() : List.of();
      if (!importing.stream().allMatch(AssetCopier::canImport)) importing = List.of();
    }
    return importing.isEmpty() ? null : TransferMode.COPY;
  }

  private void drop(DragEvent event, Path folder) {
    TransferMode mode = dropMode(event.getDragboard(), folder);
    if (mode == TransferMode.COPY) {
      List<Path> files = importing;
      javafx.application.Platform.runLater(() -> onImport.accept(files));
    }
    clearExternalDrag();
    event.setDropCompleted(mode != null);
    event.consume();
  }

  private boolean canDropMove(Path folder) {
    return dragging != null && root != null && folder != null && canMoveInto(dragging, folder);
  }

  private void dropMove(Path folder) {
    if (canDropMove(folder)) {
      Path source = dragging;
      javafx.application.Platform.runLater(() -> onMove.accept(source, folder));
    }
    clearInternalDrag();
  }

  private void clearInternalDrag() {
    dragging = null;
    setCursor(null);
    lookupAll(".drop-target").forEach(cell -> cell.getStyleClass().removeAll("drop-target"));
  }

  private void clearExternalDrag() {
    externalDragboard = null;
    importing = List.of();
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
      scrollIntoView(getRow(item));
    }
  }

  /** Scrolls only when {@code row} is off-screen, so a click does not jump the tree. */
  private void scrollIntoView(int row) {
    if (lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
      IndexedCell<?> first = flow.getFirstVisibleCell();
      IndexedCell<?> last = flow.getLastVisibleCell();
      if (first != null && last != null
          && row > first.getIndex() && row < last.getIndex()) {
        return;
      }
    }
    scrollTo(row);
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
    /** Whether the row is a folder (looked up when the row is filled, not while dragging). */
    private boolean directory;
    /** Whether the drag over this row may drop here. */
    private TransferMode accepts;

    PathTreeCell() {
      setOnDragDetected(e -> {
        Path path = getItem();
        if (path == null || root == null || path.equals(root)
            || getTreeItem() == null || getTreeItem().getParent() == null) {
          return;
        }
        startFullDrag();
        dragging = path;
        e.consume();
      });
      setOnMouseDragEntered(e -> {
        if (canDropMove(dropFolder())) getStyleClass().add("drop-target");
        e.consume();
      });
      setOnMouseDragOver(e -> {
        FileTreeView.this.setCursor(canDropMove(dropFolder())
            ? javafx.scene.Cursor.MOVE : javafx.scene.Cursor.DEFAULT);
        e.consume();
      });
      setOnMouseDragExited(e -> {
        getStyleClass().removeAll("drop-target");
        e.consume();
      });
      setOnMouseDragReleased(e -> {
        getStyleClass().removeAll("drop-target");
        dropMove(dropFolder());
        e.consume();
      });
      // decided once when the drag enters the row, not on every mouse move
      setOnDragEntered(e -> {
        accepts = dropMode(e.getDragboard(), dropFolder());
        if (accepts != null) {
          getStyleClass().add("drop-target");
        }
      });
      setOnDragOver(e -> {
        if (accepts != null) {
          e.acceptTransferModes(accepts);
        }
        e.consume();
      });
      setOnDragExited(e -> {
        accepts = null;
        getStyleClass().removeAll("drop-target");
      });
      setOnDragDropped(e -> {
        getStyleClass().removeAll("drop-target");
        accepts = null;
        drop(e, dropFolder());
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
      return directory ? path : path.getParent();
    }

    @Override
    protected void updateItem(Path path, boolean empty) {
      super.updateItem(path, empty);
      if (empty || path == null) {
        setText(null);
        setGraphic(null);
        return;
      }
      directory = Files.isDirectory(path);
      setText(path.getFileName().toString());
      var icon = Icons.forFile(path);
      icon.getStyleClass().add("tree-icon");
      setGraphic(icon);
    }
  }
}
