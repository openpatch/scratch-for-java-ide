package org.openpatch.scratch4j.ui;

import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.io.LocalHistory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The centre tab pane: one tab per open text file (a {@link CodeEditor}) plus
 * tool tabs (stage designer, paint and sound editors, asset library). Code
 * tabs autosave shortly after the last keystroke (atomic write) and report
 * every save, so diagnostics and the designer follow the code live.
 */
final class EditorTabs extends TabPane {

  private static final Duration AUTOSAVE_DELAY = Duration.millis(900);

  private final Map<Path, Tab> openTabs = new HashMap<>();
  private final Map<String, Tab> toolTabs = new HashMap<>();
  private final ApiIndex apiIndex;
  private final Supplier<Path> projectRoot;
  private Map<Path, List<CodeEditor.Diagnostic>> diagnostics = Map.of();
  private Consumer<Path> onSaved = file -> { };
  private Runnable onEdited = () -> { };
  private final CompletionContext completion = new CompletionContext();
  private static final java.util.concurrent.ExecutorService SAVE_WORKER =
      java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "editor-autosave");
        thread.setDaemon(true);
        return thread;
      });
  private record SaveRequest(Path root, CodeEditor editor, String text) {}
  private final Map<Path, SaveRequest> pendingSaves = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Set<Path> savesQueued = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final Object saveLock = new Object();
  private Consumer<IOException> onSaveFailed = error -> { };
  @FunctionalInterface
  interface SaveWriter {
    void write(Path root, Path file, String text) throws IOException;
  }
  private final SaveWriter saveWriter;
  private Consumer<String> browse = url -> { };
  private java.util.function.BiConsumer<CodeEditor, Integer> onGoToDefinition = (e, o) -> { };
  private java.util.function.BiConsumer<CodeEditor, Integer> onRename = (e, o) -> { };
  private java.util.function.BiConsumer<CodeEditor, Integer> onFindUsages = (e, o) -> { };
  private Consumer<String> onFindInProject = query -> { };

  EditorTabs(ApiIndex apiIndex, Supplier<Path> projectRoot) {
    this(apiIndex, projectRoot, LocalHistory::writeString);
  }

  EditorTabs(ApiIndex apiIndex, Supplier<Path> projectRoot, SaveWriter saveWriter) {
    this.apiIndex = apiIndex;
    this.projectRoot = projectRoot;
    this.saveWriter = saveWriter;
    setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
    getStyleClass().add("editor-tabs");
    setTabDragPolicy(TabDragPolicy.REORDER);
  }

  /** Keeps the tabs clear of what sits at the right end of their strip (the view switch). */
  void reserveHeaderEnd(javafx.beans.value.ObservableDoubleValue width) {
    // set, not styled: a style would apply the header's CSS again and could
    // leave the strip laid out at its height of that moment
    Runnable apply = () -> {
      if (lookup(".tab-header-area") instanceof javafx.scene.layout.Region header) {
        double end = width.get() > 0 ? width.get() + 20 : 0;
        if (header.getPadding().getRight() != end) {
          header.setPadding(new javafx.geometry.Insets(0, end, 0, 0));
          requestLayout();
        }
      }
    };
    // after the layout pass that changed the width
    width.addListener((o, old, w) -> javafx.application.Platform.runLater(apply));
    skinProperty().addListener((o, old, skin) -> javafx.application.Platform.runLater(apply));
    apply.run();
  }

  /** Called after a code tab was written to disk (autosave or save). */
  /** A code tab's text changed by the user (debounced like autosave's trigger). */
  void setOnEdited(Runnable onEdited) {
    this.onEdited = onEdited;
  }

  void setOnSaved(Consumer<Path> onSaved) {
    this.onSaved = onSaved;
  }

  void setOnSaveFailed(Consumer<IOException> onSaveFailed) {
    this.onSaveFailed = onSaveFailed;
  }

  void setOnGoToDefinition(java.util.function.BiConsumer<CodeEditor, Integer> action) {
    this.onGoToDefinition = action;
  }

  void setOnRename(java.util.function.BiConsumer<CodeEditor, Integer> action) {
    this.onRename = action;
  }

  void setOnFindUsages(java.util.function.BiConsumer<CodeEditor, Integer> action) {
    this.onFindUsages = action;
  }

  void setOnFindInProject(Consumer<String> action) {
    this.onFindInProject = action;
  }

  private Consumer<Path> onTypedEdit = file -> {};
  void setOnTypedEdit(Consumer<Path> action) { onTypedEdit = action; }

  private java.util.function.BiConsumer<CodeEditor, String> onBlockDropped = (e, snippet) -> { };

  /** Told about every block dragged from the palette into a code editor. */
  void setOnBlockDropped(java.util.function.BiConsumer<CodeEditor, String> action) {
    this.onBlockDropped = action;
  }

  /** Told about every gutter click (a running debug session updates its breakpoints). */
  private java.util.function.BiConsumer<Path, java.util.Set<Integer>> onBreakpointsChanged =
      (file, lines) -> { };

  void setOnBreakpointsChanged(
      java.util.function.BiConsumer<Path, java.util.Set<Integer>> listener) {
    this.onBreakpointsChanged = listener;
  }

  /** Breakpoints of every file (kept when a tab closes). */
  private final Map<Path, java.util.Set<Integer>> breakpoints = new java.util.HashMap<>();

  /** All breakpoints by file, 1-based lines. */
  Map<Path, java.util.Set<Integer>> breakpoints() {
    Map<Path, java.util.Set<Integer>> all = new java.util.HashMap<>(breakpoints);
    for (Tab tab : getTabs()) {
      if (tab.getContent() instanceof CodeEditor code) {
        all.put(code.file(), java.util.Set.copyOf(code.breakpoints()));
      }
    }
    return all;
  }

  void setOnBrowse(Consumer<String> browse) {
    this.browse = browse;
  }

  /** Opens the file in a tab (or focuses the existing tab). */
  void open(Path file) {
    Tab tab = openTabs.get(file);
    if (tab != null) {
      getSelectionModel().select(tab);
      return;
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot open " + file, e);
    }
    CodeEditor editor = new CodeEditor(file, text, apiIndex, projectRoot);
    editor.setBrowse(url -> browse.accept(url));
    String fileName = file.getFileName().toString();
    if ((fileName.endsWith(".frag") || fileName.endsWith(".vert")) && projectRoot.get() != null) {
      var shaderPanel = new javafx.scene.control.ScrollPane(
          new ShaderPreviews.Panel(editor, file, projectRoot.get()));
      shaderPanel.setFitToWidth(true);
      editor.showSidePanel("shader", shaderPanel);
    }
    editor.setSemanticCompleter((source, content, offset) -> {
      Path root = projectRoot.get();
      if (root == null) {
        return new org.openpatch.scratch4j.core.compile.Completions.Result(offset, "", false,
            List.of());
      }
      return completion.complete(root, source, content, offset);
    });
    editor.breakpoints().addAll(breakpoints.getOrDefault(file, java.util.Set.of()));
    editor.breakpoints().addListener((javafx.collections.SetChangeListener<Integer>) c -> {
      java.util.Set<Integer> lines = java.util.Set.copyOf(editor.breakpoints());
      breakpoints.put(file, lines);
      onBreakpointsChanged.accept(file, lines);
    });
    CodeEditor codeEditor = editor;
    editor.setOnGoToDefinition(offset -> onGoToDefinition.accept(codeEditor, offset));
    editor.setOnRename(offset -> onRename.accept(codeEditor, offset));
    editor.setOnFindUsages(offset -> onFindUsages.accept(codeEditor, offset));
    editor.setOnFindInProject(query -> onFindInProject.accept(query));
    editor.setOnBlockDropped(snippet -> onBlockDropped.accept(codeEditor, snippet));
    editor.setOnTypedEdit(() -> onTypedEdit.accept(file));
    editor.setDiagnostics(diagnostics.getOrDefault(file, List.of()));
    editor.setFixes(fixes.getOrDefault(file, Map.of()));
    editor.setOnFix(line -> onFix.accept(file, line));
    editor.setHints(hints.getOrDefault(file, Map.of()));
    editor.setOnHint(line -> onHint.accept(file, line));
    editor.setOnOpenFile(f -> onOpenFile.accept(f));
    tab = new Tab();
    tab.setContent(editor);
    tab.setGraphic(Icons.forFile(file));
    String name = file.getFileName().toString();
    tab.textProperty().bind(Bindings.when(editor.dirtyProperty())
        .then(name + "  ●").otherwise(name));
    tab.setTooltip(new javafx.scene.control.Tooltip(file.toString()));

    PauseTransition autosave = new PauseTransition(AUTOSAVE_DELAY);
    autosave.setOnFinished(e -> autosave(editor));
    editor.setOnEdited(() -> {
      if (editor.hasUnsavedChanges() || pendingSaves.containsKey(file)) {
        autosave.playFromStart();
        onEdited.run();
      } else {
        autosave.stop();
      }
    });
    Tab created = tab;
    tab.setOnCloseRequest(e -> {
      // an unsaved hitbox in the visual panel beside the code
      if (editor.sidePanel() instanceof SpriteAssetsView assets && !assets.confirmClose()) {
        e.consume();
        return;
      }
      if (!save(editor)) e.consume();
    });
    tab.setOnClosed(e -> {
      autosave.stop();
      editor.close();
      openTabs.remove(file, created);
    });
    tab.setContextMenu(tabMenu(tab));
    openTabs.put(file, tab);
    getTabs().add(tab);
    getSelectionModel().select(tab);
    editor.focusEditor();
  }

  /** Opens (or focuses) a tool tab whose content {@code factory} creates lazily. */
  Tab openTool(String key, String title, String icon, Supplier<Node> factory) {
    Tab tab = toolTabs.get(key);
    if (tab == null) {
      Node content = factory.get();
      if (content == null) {
        return null;
      }
      tab = new Tab(title, content);
      tab.setGraphic(Icons.of(icon));
      Tab created = tab;
      if (content instanceof SpriteAssetsView assets) {
        tab.setOnCloseRequest(e -> {
          if (!assets.confirmClose()) e.consume();
        });
      } else if (content instanceof SavableTool savable) {
        // an image editor's frame strip switches files: the title follows
        javafx.beans.value.ObservableStringValue name = content instanceof ImageEditorView image
            ? image.fileNameProperty() : new javafx.beans.property.SimpleStringProperty(title);
        tab.textProperty().bind(Bindings.concat(name,
            Bindings.when(savable.dirtyProperty()).then("  ●").otherwise("")));
        tab.setOnCloseRequest(e -> {
          if (!savable.confirmClose()) e.consume();
        });
      }
      tab.setOnClosed(e -> toolTabs.values().remove(created));
      tab.setContextMenu(tabMenu(tab));
      toolTabs.put(key, tab);
      getTabs().add(tab);
    }
    getSelectionModel().select(tab);
    return tab;
  }

  /**
   * Opens {@code file}'s code tab with a visual panel beside the code (the
   * stage designer, the sprite editor). The panel is made by {@code factory}
   * unless that code tab already shows the panel {@code key}.
   */
  CodeEditor openSide(Path file, String key, Supplier<Node> factory) {
    open(file);
    CodeEditor code = editorFor(file);
    if (code == null) return null;
    if (!key.equals(code.sidePanelKey())) {
      if (code.sidePanel() instanceof SpriteAssetsView assets && !assets.confirmClose()) {
        return code;
      }
      Node panel = factory.get();
      if (panel != null) {
        code.showSidePanel(key, panel);
        // as the student last chose: beside the code, or alone
        code.setVisualOnly(Prefs.visualOnly());
      }
      onSideChanged.run();
    }
    return code;
  }

  private Runnable onSideChanged = () -> { };

  /** Called when a visual panel opens or closes beside the code (the palette makes room). */
  void setOnSideChanged(Runnable action) {
    this.onSideChanged = action;
  }

  /** Closes the visual panel of {@code file}'s code tab (asks about an unsaved hitbox). */
  boolean closeSide(Path file) {
    CodeEditor code = editorFor(file);
    if (code == null || code.sidePanel() == null) return true;
    if (code.sidePanel() instanceof SpriteAssetsView assets && !assets.confirmClose()) {
      return false;
    }
    code.hideSidePanel();
    onSideChanged.run();
    return true;
  }

  /** Content of an open tool tab, if any. */
  /** The tool tab under {@code oldKey} now goes by {@code newKey} (it shows another file). */
  void retargetTool(String oldKey, String newKey) {
    Tab tab = toolTabs.remove(oldKey);
    if (tab != null) {
      toolTabs.put(newKey, tab);
    }
  }

  Node tool(String key) {
    Tab tab = toolTabs.get(key);
    if (tab != null) return tab.getContent();
    for (Tab t : getTabs()) {
      if (t.getContent() instanceof CodeEditor code && key.equals(code.sidePanelKey())) {
        return code.sidePanel();
      }
    }
    return null;
  }

  /** All open tool tab contents and visual panels (designers reload after code saves). */
  List<Node> tools() {
    List<Node> out = new java.util.ArrayList<>(
        toolTabs.values().stream().map(Tab::getContent).toList());
    for (Tab t : getTabs()) {
      if (t.getContent() instanceof CodeEditor code && code.sidePanel() != null) {
        out.add(code.sidePanel());
      }
    }
    return out;
  }

  /** The editor of the currently selected tab, if any (palette/F1 insert there). */
  CodeEditor activeEditor() {
    Tab tab = getSelectionModel().getSelectedItem();
    return tab != null && tab.getContent() instanceof CodeEditor editor ? editor : null;
  }

  CodeEditor editorFor(Path file) {
    Tab tab = openTabs.get(file);
    return tab != null && tab.getContent() instanceof CodeEditor editor ? editor : null;
  }

  /** Reloads a file's tab content from disk (the designer rewrote it). */
  void reloadIfOpen(Path file) {
    CodeEditor editor = editorFor(file);
    if (editor != null) {
      try {
        editor.reload(Files.readString(file, StandardCharsets.UTF_8));
      } catch (IOException e) {
        // the file is gone: the tab stays open with the last text
      }
    }
  }

  /** Opens the file (or focuses its tab) with the text from {@code start} to {@code end} selected. */
  void openRange(Path file, int start, int end) {
    open(file);
    CodeEditor editor = editorFor(file);
    if (editor != null) {
      revealCode(editor);
      editor.selectRange(start, end);
    }
  }

  /** Opens the file (or focuses its tab) and jumps to the given (1-based) line. */
  void openAt(Path file, long line) {
    open(file);
    CodeEditor editor = editorFor(file);
    if (editor != null && line > 0) {
      revealCode(editor);
      editor.gotoLine((int) line);
    }
  }

  /** A jump to a line shows the code again when only the visual editor was shown. */
  void revealCode(CodeEditor editor) {
    if (editor.visualOnly()) {
      editor.setVisualOnly(false);
      onSideChanged.run();
    }
  }

  /** Pushes diagnostics into every open editor (and remembers them for new tabs). */
  private Map<Path, Map<Integer, Boolean>> hints = Map.of();
  private java.util.function.BiConsumer<Path, Integer> onHint = (file, line) -> {};
  void setOnHint(java.util.function.BiConsumer<Path, Integer> action) { onHint = action; }
  void setHints(Map<Path, Map<Integer, Boolean>> values) {
    hints = Map.copyOf(values);
    for (var entry : openTabs.entrySet()) {
      if (entry.getValue().getContent() instanceof CodeEditor code)
        code.setHints(values.getOrDefault(entry.getKey(), Map.of()));
    }
  }
  private Map<Path, Map<Integer, String>> fixes = Map.of();
  private java.util.function.BiConsumer<Path, Integer> onFix = (file, line) -> { };

  /** Light bulbs in the gutters: per file, line -> what the fix does. */
  void setFixes(Map<Path, Map<Integer, String>> byFile) {
    this.fixes = Map.copyOf(byFile);
    for (Map.Entry<Path, Tab> entry : openTabs.entrySet()) {
      if (entry.getValue().getContent() instanceof CodeEditor editor) {
        editor.setFixes(byFile.getOrDefault(entry.getKey(), Map.of()));
      }
    }
  }

  private Consumer<Path> onOpenFile = f -> { };

  /** Opens a file a gutter preview points at (images, sounds, maps in their editors). */
  void setOnOpenFile(Consumer<Path> action) {
    this.onOpenFile = action;
  }

  void setOnFix(java.util.function.BiConsumer<Path, Integer> action) {
    this.onFix = action;
  }

  void setDiagnostics(Map<Path, List<CodeEditor.Diagnostic>> byFile) {
    this.diagnostics = Map.copyOf(byFile);
    for (Map.Entry<Path, Tab> entry : openTabs.entrySet()) {
      if (entry.getValue().getContent() instanceof CodeEditor editor) {
        editor.setDiagnostics(byFile.getOrDefault(entry.getKey(), List.of()));
      }
    }
  }

  private boolean save(CodeEditor editor) {
    try {
      if (saveNow(editor)) onSaved.accept(editor.file());
      return true;
    } catch (IOException e) {
      onSaveFailed.accept(e);
      return false;
    }
  }

  private void autosave(CodeEditor editor) {
    if (!editor.hasUnsavedChanges() && !pendingSaves.containsKey(editor.file())) return;
    SaveRequest request = new SaveRequest(projectRoot.get(), editor, editor.content());
    pendingSaves.put(editor.file(), request);
    scheduleSave(editor.file());
  }

  /** At most one running/queued job per file, even during rapid tab switches. */
  private void scheduleSave(Path file) {
    if (!savesQueued.add(file)) return;
    SAVE_WORKER.execute(() -> {
      IOException failure = null;
      SaveRequest request;
      synchronized (saveLock) {
        request = pendingSaves.get(file);
        try {
          if (request != null) saveWriter.write(request.root(), file, request.text());
        } catch (IOException e) {
          failure = e;
        }
      }
      IOException error = failure;
      if (request != null) {
        javafx.application.Platform.runLater(() -> {
          if (pendingSaves.get(file) != request) return;
          CodeEditor editor = request.editor();
          boolean current = editorFor(file) == editor
              && java.util.Objects.equals(request.root(), projectRoot.get());
          if (error == null) {
            pendingSaves.remove(file, request);
            if (current) {
              editor.markSaved(request.text());
              onSaved.accept(file);
            }
          } else if (current) {
            // Leave the request dirty/retryable, including an undo to saved text
            // while an older snapshot was being written.
            onSaveFailed.accept(error);
          }
        });
      }
      savesQueued.remove(file);
      SaveRequest latest = pendingSaves.get(file);
      if (latest != null && latest != request) scheduleSave(file);
    });
  }

  /** Tab switches save in the background, like the typing timer. */
  void autosaveAll() {
    for (Tab tab : getTabs()) {
      if (tab.getContent() instanceof CodeEditor editor) autosave(editor);
    }
  }

  /** Explicit saves/Run/close must finish in order before reading or moving files. */
  private boolean saveNow(CodeEditor editor) throws IOException {
    if (!editor.hasUnsavedChanges() && !pendingSaves.containsKey(editor.file())) return false;
    SaveRequest request = new SaveRequest(projectRoot.get(), editor, editor.content());
    pendingSaves.put(editor.file(), request); // supersede any queued older snapshot
    synchronized (saveLock) {
      saveWriter.write(request.root(), editor.file(), request.text());
      pendingSaves.remove(editor.file(), request);
    }
    editor.markSaved(request.text());
    return true;
  }

  /** Writes every dirty file back to disk. */
  void saveAll() throws IOException {
    for (Map.Entry<Path, Tab> entry : openTabs.entrySet()) {
      if (entry.getValue().getContent() instanceof CodeEditor editor) saveNow(editor);
    }
  }

  /** A project milestone includes pending edits in image, sound and map tabs too. */
  void saveForVersion() throws IOException {
    for (Node node : tools()) {
      if (node instanceof SavableTool tool && tool.dirtyProperty().get()) {
        tool.save();
        if (tool.dirtyProperty().get()) throw new IOException(I18n.t("versions.save.failed"));
      }
    }
    saveAll();
  }

  /** Files open in code tabs, in tab order (restored after a language switch). */
  List<Path> openFiles() {
    List<Path> files = new ArrayList<>();
    for (Tab tab : getTabs()) {
      if (tab.getContent() instanceof CodeEditor editor) {
        files.add(editor.file());
      }
    }
    return files;
  }

  void closeSelected() {
    Tab tab = getSelectionModel().getSelectedItem();
    if (tab != null) {
      close(tab);
    }
  }

  /** Closes {@code tab} as its close button does; false when it asked to stay open. */
  boolean close(Tab tab) {
    if (tab.getOnCloseRequest() != null) {
      javafx.event.Event request = new javafx.event.Event(tab, tab,
          Tab.TAB_CLOSE_REQUEST_EVENT);
      tab.getOnCloseRequest().handle(request);
      if (request.isConsumed()) return false;
    }
    getTabs().remove(tab);
    if (tab.getOnClosed() != null) {
      tab.getOnClosed().handle(null);
    }
    return true;
  }

  /** Drops every tab without asking (the project closes or another one opens). */
  void clear() {
    for (Tab tab : List.copyOf(getTabs())) {
      if (tab.getOnClosed() != null) tab.getOnClosed().handle(null);
    }
    getTabs().clear();
    openTabs.clear();
    toolTabs.clear();
  }

  /** Right-click on a tab: close it, the others, or all. */
  private javafx.scene.control.ContextMenu tabMenu(Tab tab) {
    javafx.scene.control.MenuItem close = new javafx.scene.control.MenuItem(
        I18n.t("tabs.close"), Icons.of("fth-x"));
    close.setOnAction(e -> close(tab));
    javafx.scene.control.MenuItem others = new javafx.scene.control.MenuItem(
        I18n.t("tabs.close.others"));
    others.setOnAction(e -> {
      for (Tab other : List.copyOf(getTabs())) {
        if (other != tab) close(other);
      }
    });
    javafx.scene.control.MenuItem all = new javafx.scene.control.MenuItem(
        I18n.t("tabs.close.all"));
    all.setOnAction(e -> List.copyOf(getTabs()).forEach(this::close));
    javafx.scene.control.ContextMenu menu =
        new javafx.scene.control.ContextMenu(close, others, all);
    menu.setOnShowing(e -> others.setDisable(getTabs().size() < 2));
    return menu;
  }

  /** The file a tab shows (code, designer, sprite, image, sound, map, font), or null. */
  static Path fileOf(Tab tab) {
    Node content = tab == null ? null : tab.getContent();
    return content instanceof CodeEditor code ? code.file()
        : content instanceof StageDesignerView designer ? designer.file()
        : content instanceof SpriteAssetsView assets ? assets.file()
        : content instanceof ImageEditorView image ? image.file()
        : content instanceof SoundEditorView sound ? sound.file()
        : content instanceof MapEditorView map ? map.file()
        : content instanceof FontPreviewView font ? font.file()
        : content instanceof PaletteEditorView palette ? palette.file() : null;
  }

  /** Close all code/designer tabs backed by a source file after a stage move. */
  void closeForFile(Path file) {
    for (Tab tab : List.copyOf(getTabs())) {
      Path tabFile = fileOf(tab);
      if (file.equals(tabFile)) {
        getTabs().remove(tab);
        if (tab.getOnClosed() != null) {
          tab.getOnClosed().handle(null);
        }
      }
    }
  }

  /** Closes tabs backed by files inside a folder that was moved or deleted. */
  void closeUnderFolder(Path folder) {
    for (Tab tab : List.copyOf(getTabs())) {
      Node content = tab.getContent();
      Path tabFile = content instanceof CodeEditor code ? code.file()
          : content instanceof StageDesignerView designer ? designer.file()
          : content instanceof SpriteAssetsView assets ? assets.file()
          : content instanceof ImageEditorView image ? image.file()
          : content instanceof SoundEditorView sound ? sound.file()
          : content instanceof MapEditorView map ? map.file()
          : content instanceof FontPreviewView font ? font.file()
          : content instanceof PaletteEditorView palette ? palette.file() : null;
      if (tabFile != null && tabFile.startsWith(folder)) {
        getTabs().remove(tab);
        if (tab.getOnClosed() != null) tab.getOnClosed().handle(null);
      }
    }
  }

  boolean confirmUnsavedHitboxes() {
    for (Tab tab : toolTabs.values()) {
      if (tab.getContent() instanceof SpriteAssetsView assets && !assets.confirmClose()) {
        return false;
      }
      if (tab.getContent() instanceof SavableTool savable && !savable.confirmClose()) {
        return false;
      }
      if (tab.getContent() instanceof CodeEditor code
          && code.sidePanel() instanceof SpriteAssetsView assets && !assets.confirmClose()) {
        return false;
      }
    }
    return true;
  }

  ObservableList<Tab> tabs() {
    return getTabs();
  }

  /**
   * What completion keeps for the open project between requests: javac's
   * state, and the list of sources and libraries (looked up again every few
   * seconds, so a new class shows up without a folder walk on every key).
   */
  private static final class CompletionContext {
    private static final long RELIST_MILLIS = 3000;
    private Path root;
    private org.openpatch.scratch4j.core.compile.Completions.Cache cache;
    private List<Path> sources = List.of();
    private List<Path> libs = List.of();
    private long listed;

    synchronized org.openpatch.scratch4j.core.compile.Completions.Result complete(Path root,
        Path source, String content, int offset) throws IOException {
      if (!root.equals(this.root)) {
        if (cache != null) cache.close();
        cache = new org.openpatch.scratch4j.core.compile.Completions.Cache();
        this.root = root;
        listed = 0;
      }
      long now = System.currentTimeMillis();
      if (now - listed > RELIST_MILLIS) {
        var project = org.openpatch.scratch4j.core.project.ScratchProject.open(root);
        sources = project.javaSources();
        libs = project.libs();
        listed = now;
      }
      return org.openpatch.scratch4j.core.compile.Completions.complete(source, content, offset,
          sources, libs, cache);
    }
  }
}
