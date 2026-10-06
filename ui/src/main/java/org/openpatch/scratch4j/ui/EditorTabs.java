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
  private Consumer<Path> onVisualMode = file -> { };
  private Consumer<String> browse = url -> { };
  private java.util.function.BiConsumer<CodeEditor, Integer> onGoToDefinition = (e, o) -> { };

  EditorTabs(ApiIndex apiIndex, Supplier<Path> projectRoot) {
    this.apiIndex = apiIndex;
    this.projectRoot = projectRoot;
    setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
    getStyleClass().add("editor-tabs");
    setTabDragPolicy(TabDragPolicy.REORDER);
  }

  /** Called after a code tab was written to disk (autosave or save). */
  void setOnSaved(Consumer<Path> onSaved) {
    this.onSaved = onSaved;
  }

  void setOnGoToDefinition(java.util.function.BiConsumer<CodeEditor, Integer> action) {
    this.onGoToDefinition = action;
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

  void setOnVisualMode(Consumer<Path> onVisualMode) {
    this.onVisualMode = onVisualMode;
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
    if (VisualMode.isStageSource(file) || VisualMode.isSpriteSource(file)
        || VisualMode.isWindowSource(file)) {
      editor.setVisualModeAction(I18n.t("mode.visual"),
          I18n.t("mode.visual.tooltip"), () -> onVisualMode.accept(file));
    }
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
      var project = org.openpatch.scratch4j.core.project.ScratchProject.open(root);
      return org.openpatch.scratch4j.core.compile.Completions.complete(source, content, offset,
          project.javaSources(), project.libs());
    });
    editor.breakpoints().addAll(breakpoints.getOrDefault(file, java.util.Set.of()));
    editor.breakpoints().addListener((javafx.collections.SetChangeListener<Integer>) c -> {
      java.util.Set<Integer> lines = java.util.Set.copyOf(editor.breakpoints());
      breakpoints.put(file, lines);
      onBreakpointsChanged.accept(file, lines);
    });
    CodeEditor codeEditor = editor;
    editor.setOnGoToDefinition(offset -> onGoToDefinition.accept(codeEditor, offset));
    editor.setDiagnostics(diagnostics.getOrDefault(file, List.of()));
    editor.setFixes(fixes.getOrDefault(file, Map.of()));
    editor.setOnFix(line -> onFix.accept(file, line));
    editor.setOnOpenFile(f -> onOpenFile.accept(f));
    tab = new Tab();
    tab.setContent(editor);
    tab.setGraphic(Icons.forFile(file));
    String name = file.getFileName().toString();
    tab.textProperty().bind(Bindings.when(editor.dirtyProperty())
        .then(name + "  ●").otherwise(name));
    tab.setTooltip(new javafx.scene.control.Tooltip(file.toString()));

    PauseTransition autosave = new PauseTransition(AUTOSAVE_DELAY);
    autosave.setOnFinished(e -> save(editor));
    editor.setOnEdited(() -> {
      if (editor.dirtyProperty().get()) {
        autosave.playFromStart();
      }
    });
    Tab created = tab;
    tab.setOnCloseRequest(e -> {
      // an unsaved hitbox in the visual panel beside the code
      if (editor.sidePanel() instanceof SpriteAssetsView assets && !assets.confirmClose()) {
        e.consume();
        return;
      }
      save(editor);
    });
    tab.setOnClosed(e -> openTabs.remove(file, created));
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
      } else if (content instanceof MapEditorView map) {
        tab.setOnCloseRequest(e -> {
          if (!map.confirmClose()) e.consume();
        });
      }
      tab.setOnClosed(e -> toolTabs.remove(key, created));
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
      if (panel != null) code.showSidePanel(key, panel);
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

  /** Opens the file (or focuses its tab) and jumps to the given (1-based) line. */
  void openAt(Path file, long line) {
    open(file);
    CodeEditor editor = editorFor(file);
    if (editor != null && line > 0) {
      editor.gotoLine((int) line);
    }
  }

  /** Pushes diagnostics into every open editor (and remembers them for new tabs). */
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

  private void save(CodeEditor editor) {
    if (!editor.hasUnsavedChanges()) {
      return;
    }
    try {
      LocalHistory.writeString(projectRoot.get(), editor.file(), editor.content());
      editor.markSaved();
      onSaved.accept(editor.file());
    } catch (IOException e) {
      // keep the dirty marker; the next save or Run retries
    }
  }

  /** Writes every dirty file back to disk. */
  void saveAll() throws IOException {
    for (Map.Entry<Path, Tab> entry : openTabs.entrySet()) {
      if (entry.getValue().getContent() instanceof CodeEditor editor && editor.hasUnsavedChanges()) {
        LocalHistory.writeString(projectRoot.get(), entry.getKey(), editor.content());
        editor.markSaved();
      }
    }
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
      if (tab.getOnCloseRequest() != null) {
        javafx.event.Event request = new javafx.event.Event(tab, tab,
            Tab.TAB_CLOSE_REQUEST_EVENT);
        tab.getOnCloseRequest().handle(request);
        if (request.isConsumed()) return;
      }
      getTabs().remove(tab);
      if (tab.getOnClosed() != null) {
        tab.getOnClosed().handle(null);
      }
    }
  }

  /** Close all code/designer tabs backed by a source file after a stage move. */
  void closeForFile(Path file) {
    for (Tab tab : List.copyOf(getTabs())) {
      Node content = tab.getContent();
      Path tabFile = content instanceof CodeEditor code ? code.file()
          : content instanceof StageDesignerView designer ? designer.file()
          : content instanceof SpriteAssetsView assets ? assets.file()
          : content instanceof ImageEditorView image ? image.file()
          : content instanceof SoundEditorView sound ? sound.file()
          : content instanceof MapEditorView map ? map.file()
          : content instanceof FontPreviewView font ? font.file() : null;
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
          : content instanceof FontPreviewView font ? font.file() : null;
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
      if (tab.getContent() instanceof MapEditorView map && !map.confirmClose()) {
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
}
