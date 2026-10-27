package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.Dragboard;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.Popup;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.assets.AssetCopier;

/**
 * The project file tree with file-type icons. Generated/build folders are
 * hidden, and so are folders in {@code assets} with nothing in them (and
 * {@code assets} itself when all of them are empty), so a new project shows
 * only its code; expansion state survives a refresh. A click opens the file in the
 * matching editor (code, paint, sound). Files and folders move by drag and
 * drop, "Move to..." or cut and paste, and F2 renames what is selected. Ctrl
 * and Shift select several rows: they move, delete and copy together. Typing
 * a name jumps to the file, Ctrl+Z brings back what was just deleted, and a
 * file with problems is red or yellow, and so are the folders it is in.
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
  private Consumer<List<Path>> onDeleteAll = paths -> { };
  private Consumer<List<Path>> onMoveAllTo = paths -> { };
  private java.util.function.BiConsumer<List<Path>, Path> onMoveAll = (paths, folder) -> { };
  private Path root;
  /** In-window drags avoid a native GTK mouse grab that can outlive the drop on XWayland. */
  private List<Path> dragging;
  /** Read a native file drag only once, rather than on every mouse move. */
  private Dragboard externalDragboard;
  private List<Path> importing = List.of();
  /** Folders made in the IDE this session: shown even while still empty. */
  private final Set<Path> keepVisible = new HashSet<>();
  private static final PseudoClass ERROR = PseudoClass.getPseudoClass("problem-error");
  private static final PseudoClass WARNING = PseudoClass.getPseudoClass("problem-warning");
  private static final PseudoClass CUT = PseudoClass.getPseudoClass("cut");
  /** Files with problems and the folders they are in: true for an error, false for a warning. */
  private Map<Path, Boolean> marks = Map.of();
  /** Ctrl+X: these move on the next paste (while the clipboard still holds them). */
  private List<Path> cut = List.of();
  private java.util.function.BiConsumer<List<Path>, Path> onPaste = (paths, folder) -> { };
  private Runnable onUndoDelete = () -> { };
  /** What Ctrl+Z would bring back, or null. */
  private String undoDelete;
  /** Typed letters jump to the next file whose name starts with (or contains) them. */
  private final StringBuilder query = new StringBuilder();
  private final Label queryLabel = new Label();
  private final Popup queryPopup = new Popup();
  private final PauseTransition queryTimeout = new PauseTransition(Duration.seconds(2));

  FileTreeView() {
    setShowRoot(false);
    // the card around the tree draws the border
    getStyleClass().addAll("file-tree", "edge-to-edge");
    getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
    setCellFactory(tv -> new PathTreeCell());
    setOnMouseClicked(e -> {
      TreeItem<Path> item = getSelectionModel().getSelectedItem();
      // Ctrl or Shift adds to the selection; only a plain click opens
      if (item != null && Files.isRegularFile(item.getValue()) && !several()
          && !e.isShortcutDown() && !e.isShiftDown()
          && e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.isStillSincePress()) {
        onOpen.accept(item.getValue());
      }
    });
    queryLabel.getStyleClass().add("tree-query");
    queryPopup.getContent().add(queryLabel);
    queryTimeout.setOnFinished(e -> clearQuery());
    focusedProperty().addListener((o, was, is) -> {
      if (!is) clearQuery();
    });
    addEventHandler(KeyEvent.KEY_TYPED, this::typed);
    addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (query.isEmpty()) return;
      if (e.getCode() == KeyCode.BACK_SPACE) {
        query.setLength(query.length() - 1);
        if (query.isEmpty()) clearQuery();
        else jumpTo(false);
        e.consume();
      } else if (e.getCode() == KeyCode.ESCAPE) {
        clearQuery();
        e.consume();
      } else if (e.getCode() == KeyCode.ENTER) {
        clearQuery(); // and open what was found
      } else if (e.getCode().isNavigationKey() || e.isShortcutDown()) {
        clearQuery();
      }
    });
    setOnKeyPressed(e -> {
      TreeItem<Path> item = getSelectionModel().getSelectedItem();
      if (e.isShortcutDown() && !e.isAltDown() && !e.isShiftDown()
          && (e.getCode() == KeyCode.C || e.getCode() == KeyCode.X
              || e.getCode() == KeyCode.V || e.getCode() == KeyCode.Z)) {
        switch (e.getCode()) {
          case C -> copyFiles(false);
          case X -> copyFiles(true);
          case V -> paste();
          default -> {
            if (undoDelete != null) onUndoDelete.run();
          }
        }
        e.consume();
      } else if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && dragging != null) {
        clearInternalDrag();
        e.consume();
      } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER && item != null
          && !several() && Files.isRegularFile(item.getValue())) {
        onOpen.accept(item.getValue());
      } else if (e.getCode() == javafx.scene.input.KeyCode.DELETE && several()) {
        onDeleteAll.accept(selectedPaths());
      } else if (e.getCode() == javafx.scene.input.KeyCode.DELETE
          && selectedFile() != null) {
        onDeleteFile.accept(selectedFile());
      } else if (e.getCode() == javafx.scene.input.KeyCode.DELETE
          && selectedDirectory() != null) {
        onDeleteFolder.accept(selectedDirectory());
      } else if (e.getCode() == javafx.scene.input.KeyCode.F2 && !several()) {
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
    moveTo.setOnAction(e -> {
      if (several()) onMoveAllTo.accept(selectedPaths());
      else onMoveTo.accept(selectedPath());
    });
    MenuItem duplicate = new MenuItem(I18n.t("tree.duplicate"), Icons.of("fth-copy"));
    duplicate.setOnAction(e -> onDuplicate.accept(selectedFile()));
    MenuItem copyPath = new MenuItem(I18n.t("tree.copypath"), Icons.of("fth-clipboard"));
    copyPath.setOnAction(e -> copyPath());
    MenuItem editSprite = new MenuItem(I18n.t("spriteassets.open.short"),
        Icons.of("fth-image"));
    editSprite.setOnAction(e -> onEditSprite.accept(selectedFile()));
    MenuItem deleteAll = new MenuItem("", Icons.of("fth-trash-2"));
    deleteAll.setOnAction(e -> onDeleteAll.accept(selectedPaths()));
    // no accelerators: a context menu's would act in the whole window
    MenuItem cutItem = new MenuItem(I18n.t("tree.cut"), Icons.of("fth-scissors"));
    cutItem.setOnAction(e -> copyFiles(true));
    MenuItem copyItem = new MenuItem(I18n.t("tree.copy"), Icons.of("fth-copy"));
    copyItem.setOnAction(e -> copyFiles(false));
    MenuItem pasteItem = new MenuItem(I18n.t("tree.paste"), Icons.of("fth-clipboard"));
    pasteItem.setOnAction(e -> paste());
    MenuItem undoItem = new MenuItem("", Icons.of("fth-rotate-ccw"));
    undoItem.setOnAction(e -> onUndoDelete.run());
    ContextMenu menu = new ContextMenu(newClass, newFolder, refresh, reveal,
        new javafx.scene.control.SeparatorMenuItem(), editSprite, rename, moveTo, duplicate,
        cutItem, copyItem, pasteItem, copyPath, deleteFile, deleteFolder, deleteAll, undoItem);
    menu.setOnShowing(e -> {
      boolean many = several();
      cutItem.setVisible(!selectedPaths().isEmpty());
      copyItem.setVisible(!selectedPaths().isEmpty());
      pasteItem.setVisible(root != null && Clipboard.getSystemClipboard().hasFiles());
      undoItem.setVisible(undoDelete != null);
      if (undoDelete != null) undoItem.setText(I18n.t("tree.undo.delete", undoDelete));
      deleteAll.setText(I18n.t("tree.delete.many", selectedPaths().size()));
      deleteAll.setVisible(many);
      copyPath.setText(I18n.t(many ? "tree.copypaths" : "tree.copypath"));
      boolean folder = !many && selectedDirectory() != null;
      boolean file = !many && selectedFile() != null;
      editSprite.setVisible(!many && VisualMode.isSpriteSource(selectedFile()));
      rename.setVisible(file || folder);
      moveTo.setVisible(file || folder || many);
      duplicate.setVisible(file);
      copyPath.setVisible(file || folder || many);
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

  /** Delete with several rows selected: the files and folders, none inside another. */
  void setOnDeleteAll(Consumer<List<Path>> action) {
    onDeleteAll = action;
  }

  /** "Move to..." with several rows selected: the host asks for one folder for all. */
  void setOnMoveAllTo(Consumer<List<Path>> action) {
    onMoveAllTo = action;
  }

  /** A drag and drop of several rows: what moves and the folder it was dropped on. */
  void setOnMoveAll(java.util.function.BiConsumer<List<Path>, Path> action) {
    onMoveAll = action;
  }

  /** Ctrl+V: copies of files on the clipboard (ours or the file manager's) into the folder. */
  void setOnPaste(java.util.function.BiConsumer<List<Path>, Path> action) {
    onPaste = action;
  }

  /** Ctrl+Z: brings back the last delete; {@code what} names it in the menu (null: nothing). */
  void setOnUndoDelete(Runnable action) {
    onUndoDelete = action;
  }

  void setUndoDelete(String what) {
    undoDelete = what;
  }

  /**
   * Marks files with problems, and the folders above them: an error wins
   * over a warning. {@code files} maps a file to whether it has an error.
   */
  void setProblems(Map<Path, Boolean> files) {
    Map<Path, Boolean> marked = new HashMap<>();
    Path base = root == null ? null : root.toAbsolutePath().normalize();
    files.forEach((file, error) -> {
      for (Path p = file.toAbsolutePath().normalize(); p != null && base != null
           && p.startsWith(base) && !p.equals(base); p = p.getParent()) {
        marked.merge(p, error, Boolean::logicalOr);
      }
    });
    if (!marked.equals(marks)) {
      marks = marked;
      refresh();
    }
  }

  /** Ctrl+C and Ctrl+X: the files on the system clipboard, and their paths as text. */
  private void copyFiles(boolean move) {
    List<Path> paths = selectedPaths();
    if (paths.isEmpty()) return;
    ClipboardContent content = new ClipboardContent();
    content.putFiles(paths.stream().map(Path::toFile).toList());
    content.putString(String.join("\n", paths.stream().map(this::relative).toList()));
    Clipboard.getSystemClipboard().setContent(content);
    cut = move ? paths : List.of();
    refresh();
  }

  /** Ctrl+V into the selected folder (or the selected file's): a cut moves, a copy copies. */
  private void paste() {
    Clipboard clipboard = Clipboard.getSystemClipboard();
    Path folder = folderForCreation();
    if (root == null || folder == null || !clipboard.hasFiles()) return;
    List<Path> files = clipboard.getFiles().stream().map(java.io.File::toPath).toList();
    List<Path> moving = cut;
    if (!moving.isEmpty() && files.equals(moving)) {
      cut = List.of();
      clipboard.clear();
      refresh();
      if (moving.size() == 1) onMove.accept(moving.getFirst(), folder);
      else onMoveAll.accept(moving, folder);
    } else {
      onPaste.accept(files, folder);
    }
  }

  private void typed(KeyEvent e) {
    String typed = e.getCharacter();
    if (typed.isEmpty() || e.isShortcutDown() || e.isAltDown() || e.isControlDown()) return;
    char c = typed.charAt(0);
    // a space starts no search; inside one it is part of the name
    if (c < ' ' || c == 127 || (c == ' ' && query.isEmpty())) return;
    query.append(c);
    jumpTo(query.length() == 1);
    e.consume();
  }

  /**
   * Selects the first row from the selected one on (after it, for a new
   * search) whose name starts with the typed letters, else one that contains
   * them, opening the folders above it. Collapsed folders are searched too.
   */
  private void jumpTo(boolean next) {
    queryTimeout.playFromStart();
    List<TreeItem<Path>> items = new java.util.ArrayList<>();
    if (getRoot() != null) flatten(getRoot(), items);
    List<String> names = items.stream()
        .map(item -> item.getValue().getFileName().toString()).toList();
    int from = Math.max(0, items.indexOf(getSelectionModel().getSelectedItem()));
    int found = ProjectTree.match(names, next ? from + 1 : from, query.toString());
    queryLabel.setText(query.toString());
    queryLabel.pseudoClassStateChanged(ERROR, found < 0);
    if (found >= 0) selectPath(items.get(found).getValue());
    if (!queryPopup.isShowing() && getScene() != null) {
      var corner = localToScreen(4, getHeight() - 32);
      if (corner != null) queryPopup.show(this, corner.getX(), corner.getY());
    }
  }

  private static void flatten(TreeItem<Path> item, List<TreeItem<Path>> into) {
    for (TreeItem<Path> child : item.getChildren()) {
      into.add(child);
      flatten(child, into);
    }
  }

  private void clearQuery() {
    query.setLength(0);
    queryTimeout.stop();
    queryPopup.hide();
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

  /** Copies the project-relative path ("assets/images/cat.png"), as code names it; one per line. */
  private void copyPath() {
    List<Path> paths = selectedPaths();
    if (paths.isEmpty() || root == null) return;
    javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
    content.putString(String.join("\n", paths.stream().map(this::relative).toList()));
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

  /** Whether more than one row is selected. */
  private boolean several() {
    return getSelectionModel().getSelectedItems().size() > 1;
  }

  /**
   * The selected files and folders of the project, in tree order, without
   * anything inside a selected folder (it goes with its folder).
   */
  List<Path> selectedPaths() {
    if (root == null) return List.of();
    Path base = root.toAbsolutePath().normalize();
    List<Path> paths = new java.util.ArrayList<>();
    for (TreeItem<Path> item : List.copyOf(getSelectionModel().getSelectedItems())) {
      Path path = item == null ? null : item.getValue();
      if (path == null || path.equals(root) || !Files.exists(path)
          || !path.toAbsolutePath().normalize().startsWith(base)) continue;
      paths.add(path);
    }
    paths.sort(java.util.Comparator.comparingInt(p -> getRow(find(getRoot(), p))));
    return paths.stream()
        .filter(p -> paths.stream().noneMatch(other -> !other.equals(p)
            && p.toAbsolutePath().normalize().startsWith(other.toAbsolutePath().normalize())))
        .toList();
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
    return dragging != null && root != null && folder != null
        && dragging.stream().allMatch(path -> canMoveInto(path, folder));
  }

  private void dropMove(Path folder) {
    if (canDropMove(folder)) {
      List<Path> sources = dragging;
      javafx.application.Platform.runLater(() -> {
        if (sources.size() == 1) onMove.accept(sources.getFirst(), folder);
        else onMoveAll.accept(sources, folder);
      });
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
    marks = Map.of();
    cut = List.of();
    undoDelete = null;
    setRoot((TreeItem<Path>) null);
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
    if (getRoot() != null && shape(getRoot()).equals(shape(built))) {
      return; // nothing came or went: keep the selection and the scroll position
    }
    List<Path> selected = getSelectionModel().getSelectedItems().stream()
        .filter(java.util.Objects::nonNull).map(TreeItem::getValue).toList();
    int top = lookup(".virtual-flow") instanceof VirtualFlow<?> flow
        && flow.getFirstVisibleCell() != null ? flow.getFirstVisibleCell().getIndex() : -1;
    setRoot(built);
    for (Path path : selected) {
      TreeItem<Path> item = find(built, path);
      if (item != null) getSelectionModel().select(item);
    }
    if (top >= 0) scrollTo(Math.min(top, Math.max(0, getExpandedItemCount() - 1)));
  }

  /** Every path of the tree in order, and which folders are open. */
  private static List<String> shape(TreeItem<Path> item) {
    List<TreeItem<Path>> items = new java.util.ArrayList<>();
    flatten(item, items);
    return items.stream().map(i -> i.getValue() + (i.isExpanded() ? "/" : "")).toList();
  }

  void selectPath(Path path) {
    TreeItem<Path> item = find(getRoot(), path);
    if (item != null) {
      for (TreeItem<Path> parent = item.getParent(); parent != null;
           parent = parent.getParent()) parent.setExpanded(true);
      getSelectionModel().clearAndSelect(getRow(item));
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
        // a selected row takes the other selected rows along
        List<Path> selected = selectedPaths();
        dragging = getTreeItem() != null && getTreeView().getSelectionModel().getSelectedItems()
            .contains(getTreeItem()) && selected.size() > 1 ? selected : List.of(path);
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
      // the context menu acts on the selection the row is part of, else on the row alone
      setOnMousePressed(e -> {
        if (e.isSecondaryButtonDown()) selectForMenu();
      });
      setOnContextMenuRequested(e -> selectForMenu());
    }

    private void selectForMenu() {
      var model = getTreeView().getSelectionModel();
      if (getTreeItem() != null && !model.getSelectedItems().contains(getTreeItem())) {
        model.clearAndSelect(getIndex());
      }
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
      Boolean mark = empty || path == null ? null
          : marks.get(path.toAbsolutePath().normalize());
      pseudoClassStateChanged(ERROR, Boolean.TRUE.equals(mark));
      pseudoClassStateChanged(WARNING, Boolean.FALSE.equals(mark));
      pseudoClassStateChanged(CUT, !empty && path != null && cut.contains(path));
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
