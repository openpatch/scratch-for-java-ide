package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableSet;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.api.ApiMethod;
import org.openpatch.scratch4j.core.assets.BuiltinAssetIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The code editor: a RichTextFX {@link CodeArea} with syntax highlighting,
 * line numbers with error markers, bracket matching, auto-indent, comment
 * toggle, font zoom, a find/replace bar, error squiggles with beginner
 * explanations on hover, and completion at the caret (library API, Java
 * keywords, words in the file, and built-in/project asset names inside
 * {@code addCostume("…")}, {@code addSound("…")} ...).
 */
final class CodeEditor extends BorderPane implements BlockPalette.DragTarget {

  /** One diagnostic shown in the editor (1-based line/column; column 0 = whole line). */
  record Diagnostic(long line, long column, String message, String explanation,
      boolean error) {}

  /**
   * One completion entry. {@code text} is the name to insert; {@code label}
   * includes the parameters for methods. {@code noArgs} tells whether the caret
   * lands after "()", {@code docsUrl} links the reference page.
   */
  record Completion(String text, String detail, Kind kind, Boolean noArgs, String docsUrl,
      String label) {
    enum Kind { METHOD, FIELD, VARIABLE, CLASS, KEYWORD, WORD, IMAGE, SOUND, FILE }

    Completion(String text, String detail, Kind kind) {
      this(text, detail, kind, null, null, text);
    }
  }

  /** Type-aware completion for the editor's file (javac-backed, may be slow). */
  interface SemanticCompleter {
    org.openpatch.scratch4j.core.compile.Completions.Result complete(Path file, String text,
        int offset) throws java.io.IOException;
  }

  private static final java.util.concurrent.ExecutorService COMPLETION_WORKER =
      java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "semantic-completion");
        t.setDaemon(true);
        return t;
      });

  private static final String INDENT = "  ";
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*$");
  private static final Pattern WORD = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]{2,}\\b");
  private static final Pattern IMAGE_STRING = Pattern.compile(
      "\\b(addCostume|addBackdrop|switchCostumeTo|switchBackdropTo|addAnimation|setCostume)"
          + "\\((?:\"[^\"]*\"\\s*,\\s*)?\"([^\"]*)$");
  private static final Pattern MAP_STRING = Pattern.compile(
      "\\bnew\\s+TiledMap\\(\"([^\"]*)$");
  private static final Pattern LAYER_STRING = Pattern.compile(
      "\\b(getObjectsFromLayer|spawn|stampLayerToBackground|stampLayerToForeground)"
          + "\\(\"([^\"]*)$");
  private static final Pattern SOUND_STRING = Pattern.compile(
      "\\b(addSound|playSound|startSound|playSoundUntilDone)"
          + "\\((?:\"[^\"]*\"\\s*,\\s*)?\"([^\"]*)$");
  private static final List<String> JAVA_KEYWORDS = List.of(
      "public", "private", "protected", "class", "extends", "implements", "void",
      "int", "double", "float", "boolean", "String", "if", "else", "while", "for", "return",
      "new", "static", "final", "import", "package", "true", "false", "null", "this",
      "super", "switch", "case", "break", "continue", "var");

  /** Shared editor font size (Ctrl +/-/0, Ctrl+wheel), persisted. */
  static final IntegerProperty FONT_SIZE = new SimpleIntegerProperty(Prefs.editorFontSize());

  static {
    FONT_SIZE.addListener((o, old, size) -> Prefs.editorFontSize(size.intValue()));
  }

  private final Path file;
  private final ApiIndex apiIndex;
  private final Supplier<Path> projectRoot;
  private final SyntaxHighlighter.Language language;
  private final CodeArea area = new CodeArea();
  private final FindBar findBar;
  private final BooleanProperty dirty = new SimpleBooleanProperty(false);
  private final ObservableSet<Integer> errorLines = FXCollections.observableSet(new HashSet<>());
  private final ObservableSet<Integer> warningLines =
      FXCollections.observableSet(new HashSet<>());

  private List<Diagnostic> diagnostics = List.of();
  private final List<int[]> diagnosticRanges = new ArrayList<>();
  private final List<Integer> bracketMarks = new ArrayList<>();
  private String savedText;
  private Runnable onEdited = () -> { };
  private java.util.function.IntConsumer onGoToDefinition = offset -> { };
  private java.util.function.IntConsumer onRename = offset -> { };
  private java.util.function.IntConsumer onFindUsages = offset -> { };
  private java.util.function.Consumer<String> onFindInProject = query -> { };

  private final Popup completionPopup = new Popup();
  private final ListView<Completion> completionList = new ListView<>();
  private final Label completionDetail = new Label();
  private int completionStart = -1;
  private SemanticCompleter semanticCompleter;
  private java.util.function.Consumer<String> browse = url -> { };
  private final javafx.scene.control.Hyperlink completionDocs = new javafx.scene.control.Hyperlink();
  /** Semantic items for the identifier starting at {@link #semanticStart} (reused while typing). */
  private List<Completion> semanticItems;
  private int semanticStart = -1;
  private boolean semanticMemberAccess;
  private long semanticRequest;
  private final Popup hoverPopup = new Popup();

  CodeEditor(Path file, String text, ApiIndex apiIndex, Supplier<Path> projectRoot) {
    this.file = file;
    this.apiIndex = apiIndex;
    this.projectRoot = projectRoot;
    this.language = SyntaxHighlighter.languageOf(file);
    this.savedText = text;

    area.getStyleClass().add("code-editor");
    area.setParagraphGraphicFactory(gutter());
    area.replaceText(text);
    area.getUndoManager().forgetHistory();
    area.moveTo(0);
    area.requestFollowCaret();
    area.styleProperty().bind(FONT_SIZE.asString("-fx-font-size: %dpx;"));
    rehighlight();
    updateRegions();

    area.multiPlainChanges().successionEnds(Duration.ofMillis(40)).subscribe(ignore -> {
      rehighlight();
      updateRegions();
      dirty.set(!area.getText().equals(savedText));
      onEdited.run();
    });
    area.caretPositionProperty().addListener((o, old, pos) -> {
      markBrackets();
      int paragraph = area.getCurrentParagraph();
      if (paragraph != lastCaretParagraph) {
        lastCaretParagraph = paragraph;
        if (area.isFocused()) onCaretLine.accept(caretLineText());
      }
    });

    installKeys();
    installHover();
    installCompletionPopup();
    installBlockDrop();

    findBar = new FindBar(area);
    codeColumn.setTop(findBar);
    // previews of blocks dragged in are drawn above the code
    codeColumn.setCenter(new StackPane(new VirtualizedScrollPane<>(area), dropLayer));
    codeColumn.getStyleClass().add("code-column");
    codeSide.setCenter(codeColumn);
    codeSide.getStyleClass().add("code-side");
    // a block dragged from the palette is drawn above both the palette and the code
    dragLayer.setMouseTransparent(true);
    dragLayer.setManaged(false);
    codeHolder.getChildren().setAll(codeSide, dragLayer);
    setCenter(codeHolder);
    installLinks();
    installContextMenu();
    area.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
      if (e.isShortcutDown() && e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
        clearLink();
        area.hit(e.getX(), e.getY()).getCharacterIndex().ifPresent(index -> {
          area.moveTo(index);
          onGoToDefinition.accept(index);
        });
        e.consume();
      }
    });
  }

  /*
   * The tab's layout, left to right as in Scratch: the blocks, the code, the
   * visual editor. The blocks and the code are the code side; the visual
   * editor sits beside it, or alone when the code is hidden.
   */
  /** The find bar above the code. */
  private final BorderPane codeColumn = new BorderPane();
  /** The block pane (when docked here) and the code. */
  private final BorderPane codeSide = new BorderPane();
  /** The code side with the layer a block dragged from the palette is drawn on. */
  private final StackPane codeHolder = new StackPane();
  private final javafx.scene.layout.Pane dragLayer = new javafx.scene.layout.Pane();
  private javafx.scene.Node sidePanel;
  private String sidePanelKey;
  private javafx.scene.control.SplitPane split;
  private boolean visualOnly;
  /** Where the divider between code and visual editor was (the code's share). */
  private double splitPosition = 0.42;

  /**
   * The class's visual editor (stage designer, sprite editor) to the right
   * of the code, in this tab; replaces another one that was there.
   */
  void showSidePanel(String key, javafx.scene.Node panel) {
    if (split != null) {
      rememberSplit();
      split.getItems().clear();
    }
    sidePanel = panel;
    sidePanelKey = key;
    split = new javafx.scene.control.SplitPane();
    split.getStyleClass().add("code-visual-split");
    layoutSides();
  }

  /** Back to the code alone. */
  void hideSidePanel() {
    if (sidePanel == null) return;
    rememberSplit();
    split.getItems().clear();
    split = null;
    sidePanel = null;
    sidePanelKey = null;
    visualOnly = false;
    setCenter(codeHolder);
  }

  /** The visual editor alone (the code hidden), or back beside the code. */
  void setVisualOnly(boolean only) {
    if (sidePanel == null || only == visualOnly) return;
    rememberSplit();
    visualOnly = only;
    layoutSides();
  }

  boolean visualOnly() {
    return sidePanel != null && visualOnly;
  }

  private void rememberSplit() {
    if (split != null && split.getItems().size() == 2) {
      splitPosition = split.getDividers().get(0).getPosition();
    }
  }

  private void layoutSides() {
    if (visualOnly) {
      split.getItems().clear();
      setCenter(sidePanel);
    } else {
      setCenter(null);
      split.getItems().setAll(codeHolder, sidePanel);
      split.setDividerPositions(splitPosition);
      setCenter(split);
    }
  }

  /** The block pane at the left of the code (one pane, moved to the tab shown). */
  void dockPalette(javafx.scene.Node palette) {
    if (codeSide.getLeft() != palette) codeSide.setLeft(palette);
    if (palette instanceof BlockPalette blocks) blocks.setDragTarget(this);
  }

  void undockPalette() {
    if (codeSide.getLeft() instanceof BlockPalette blocks) blocks.setDragTarget(null);
    codeSide.setLeft(null);
    endDragChip();
  }

  /** The block pane docked here, or null. */
  javafx.scene.Node dockedPalette() {
    return codeSide.getLeft();
  }

  javafx.scene.Node sidePanel() {
    return sidePanel;
  }

  String sidePanelKey() {
    return sidePanelKey;
  }

  /** How the tab shows now: the code, both, or the visual editor alone. */
  ViewSwitch.View view() {
    return sidePanel == null ? ViewSwitch.View.CODE
        : visualOnly ? ViewSwitch.View.VISUAL : ViewSwitch.View.SPLIT;
  }

  /** The find bar's row (above the code only). */
  BorderPane codeColumn() {
    return codeColumn;
  }

  /** F12 or Ctrl/Cmd+click: the host resolves the name at the offset. */
  void setOnGoToDefinition(java.util.function.IntConsumer action) {
    this.onGoToDefinition = action;
  }

  /** F2: the host renames the name at the offset everywhere. */
  void setOnRename(java.util.function.IntConsumer action) {
    this.onRename = action;
  }

  /** Shift+F12: the host lists every use of the name at the offset. */
  void setOnFindUsages(java.util.function.IntConsumer action) {
    this.onFindUsages = action;
  }

  /** Ctrl+Shift+H: the host searches all project files (the selection or word as a start). */
  void setOnFindInProject(java.util.function.Consumer<String> action) {
    this.onFindInProject = action;
  }

  /** The text of the caret's line (visual mode selects what it names). */
  /** The caret's line, 1-based. */
  int caretLine() {
    return area.getCurrentParagraph() + 1;
  }

  // --- Ctrl/Cmd+hover: the name under the mouse looks like a link -----------------

  private int[] link;
  private StyleSpans<Collection<String>> linkStyles;

  private void installLinks() {
    area.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
      if (e.isShortcutDown()) {
        showLinkAt(e.getX(), e.getY());
      } else {
        clearLink();
      }
    });
    area.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> clearLink());
    area.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
      if (!e.isShortcutDown()) clearLink();
    });
    area.textProperty().addListener((o, a, b) -> {
      // the text changed: the saved styles no longer fit
      link = null;
      linkStyles = null;
      area.setCursor(null);
    });
  }

  /** The identifier at a text offset, as {start, end}, or null. */
  int[] wordAt(int index) {
    String text = area.getText();
    if (index < 0 || index > text.length()) return null;
    int start = index;
    int end = index;
    while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
    while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
    if (start == end || !Character.isJavaIdentifierStart(text.charAt(start))) return null;
    return new int[] {start, end};
  }

  /** Underlines the name at a point of the text area. */
  void showLinkAt(double x, double y) {
    var hit = area.hit(x, y).getCharacterIndex();
    if (hit.isPresent()) {
      showLinkAtIndex(hit.getAsInt());
    } else {
      clearLink();
    }
  }

  /** Underlines the name at a text offset (hand cursor; a click goes to it). */
  void showLinkAtIndex(int index) {
    int[] word = wordAt(index);
    if (word != null && link != null && word[0] == link[0] && word[1] == link[1]) return;
    clearLink();
    if (word == null) return;
    String name = area.getText(word[0], word[1]);
    if (SyntaxHighlighter.isKeyword(name)) return;
    linkStyles = area.getStyleSpans(word[0], word[1]);
    area.setStyleSpans(word[0], linkStyles.mapStyles(styles -> {
      List<String> withLink = new ArrayList<>(styles);
      withLink.add("code-link");
      return withLink;
    }));
    link = word;
    area.setCursor(javafx.scene.Cursor.HAND);
  }

  void clearLink() {
    if (link != null && linkStyles != null && link[1] <= area.getLength()) {
      area.setStyleSpans(link[0], linkStyles);
    }
    link = null;
    linkStyles = null;
    area.setCursor(null);
  }

  /** The underlined name (tests). */
  String linkText() {
    return link == null ? null : area.getText(link[0], link[1]);
  }

  // --- right-click ------------------------------------------------------------

  private void installContextMenu() {
    javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
    area.setContextMenu(menu);
    area.setOnContextMenuRequested(e -> {
      // the click moves the caret (unless it is inside the selection)
      var hit = area.hit(e.getX(), e.getY()).getCharacterIndex();
      if (hit.isPresent() && (area.getSelection().getLength() == 0
          || hit.getAsInt() < area.getSelection().getStart()
          || hit.getAsInt() > area.getSelection().getEnd())) {
        area.moveTo(hit.getAsInt());
      }
      menu.getItems().setAll(contextMenuItems());
    });
  }

  /** The editor's right-click menu at the caret (tests read it). */
  List<javafx.scene.control.MenuItem> contextMenuItems() {
    List<javafx.scene.control.MenuItem> items = new ArrayList<>();
    int caret = area.getCaretPosition();
    int[] word = wordAt(caret);
    if (word != null && !SyntaxHighlighter.isKeyword(area.getText(word[0], word[1]))) {
      String name = area.getText(word[0], word[1]);
      javafx.scene.control.MenuItem go = new javafx.scene.control.MenuItem(
          I18n.t("editor.menu.definition", name), Icons.of("fth-corner-down-right"));
      go.setAccelerator(new javafx.scene.input.KeyCodeCombination(KeyCode.F12));
      go.setOnAction(e -> onGoToDefinition.accept(word[0]));
      javafx.scene.control.MenuItem usages = new javafx.scene.control.MenuItem(
          I18n.t("editor.menu.usages", name), Icons.of("fth-list"));
      usages.setAccelerator(new javafx.scene.input.KeyCodeCombination(KeyCode.F12,
          javafx.scene.input.KeyCombination.SHIFT_DOWN));
      usages.setOnAction(e -> onFindUsages.accept(word[0]));
      javafx.scene.control.MenuItem rename = new javafx.scene.control.MenuItem(
          I18n.t("editor.menu.rename", name), Icons.of("fth-edit-3"));
      rename.setAccelerator(new javafx.scene.input.KeyCodeCombination(KeyCode.F2));
      rename.setOnAction(e -> onRename.accept(word[0]));
      items.addAll(List.of(go, usages, rename));
      items.add(new javafx.scene.control.SeparatorMenuItem());
    }
    javafx.scene.control.MenuItem cut = new javafx.scene.control.MenuItem(
        I18n.t("editor.menu.cut"), Icons.of("fth-scissors"));
    cut.setOnAction(e -> area.cut());
    cut.setDisable(area.getSelection().getLength() == 0);
    javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem(
        I18n.t("editor.menu.copy"), Icons.of("fth-copy"));
    copy.setOnAction(e -> area.copy());
    copy.setDisable(area.getSelection().getLength() == 0);
    javafx.scene.control.MenuItem paste = new javafx.scene.control.MenuItem(
        I18n.t("editor.menu.paste"), Icons.of("fth-clipboard"));
    paste.setOnAction(e -> area.paste());
    javafx.scene.control.MenuItem all = new javafx.scene.control.MenuItem(
        I18n.t("editor.menu.selectall"));
    all.setOnAction(e -> area.selectAll());
    items.addAll(List.of(cut, copy, paste, all));
    return items;
  }

  String caretLineText() {
    return area.getParagraph(area.getCurrentParagraph()).getText();
  }

  // --- public surface --------------------------------------------------------

  Path file() {
    return file;
  }

  /** Rebuilds the gutter, so line previews re-read images saved elsewhere. */
  void refreshPreviews() {
    area.setParagraphGraphicFactory(gutter());
  }

  CodeArea area() {
    return area;
  }

  String content() {
    return area.getText();
  }

  BooleanProperty dirtyProperty() {
    return dirty;
  }

  /** Unsaved changes right now (the property lags behind typing by the debounce). */
  boolean hasUnsavedChanges() {
    return !area.getText().equals(savedText);
  }

  /** Called (debounced) after every edit. */
  void setOnEdited(Runnable onEdited) {
    this.onEdited = onEdited;
  }

  /** The text was written to disk. */
  void markSaved() {
    savedText = area.getText();
    dirty.set(false);
  }

  /** Replaces the text (file changed on disk), keeping the caret line. */
  void reload(String text) {
    if (text.equals(area.getText())) {
      markSaved();
      return;
    }
    // only the part that changed: one undo step in the code's history (a designer move
    // is undone with Ctrl+Z like typing), caret and scroll position stay
    String old = area.getText();
    int start = 0;
    int max = Math.min(old.length(), text.length());
    while (start < max && old.charAt(start) == text.charAt(start)) start++;
    int endOld = old.length();
    int endNew = text.length();
    while (endOld > start && endNew > start && old.charAt(endOld - 1) == text.charAt(endNew - 1)) {
      endOld--;
      endNew--;
    }
    area.replaceText(start, endOld, text.substring(start, endNew));
    savedText = text;
    dirty.set(false);
  }

  /** One step back in the code's history (also designer changes made beside it). */
  void undo() {
    if (area.isUndoAvailable()) area.undo();
  }

  void redo() {
    if (area.isRedoAvailable()) area.redo();
  }

  /** Inserts text at the caret (palette, asset library), re-indented to the caret line. */
  void insertAtCaret(String text) {
    String indent = currentIndent();
    String indented = text.replace("\n", "\n" + indent);
    if (indented.endsWith("\n" + indent)) {
      indented = indented.substring(0, indented.length() - indent.length());
    }
    area.replaceSelection(indented);
    area.requestFocus();
  }

  /**
   * Inserts a palette snippet at the caret (a double-click on a block, the
   * asset library). A reporter goes at the caret; a stack block on a new line
   * below the caret line (or on the caret line when it is blank); an event
   * block in the class body, after the method the caret is in, and when the
   * class already has that method the caret goes into it instead. Where the
   * block can't go (the rules of {@link BlockDrop}), nothing changes and a
   * note at the caret says what to do. Whether code went in.
   */
  boolean insertBlock(String snippet) {
    String text = area.getText();
    BlockDrop.Kind kind = BlockDrop.kindOf(snippet);
    if (kind == BlockDrop.Kind.EXPRESSION) {
      BlockDrop.Target target = BlockDrop.inline(text, area.getSelection().getStart(), snippet);
      if (!target.valid()) return refuseAtCaret(target);
      insertAtCaret(snippet);
      return true;
    }
    int paragraph = area.getCurrentParagraph();
    if (kind == BlockDrop.Kind.MEMBER) {
      // an event block is a method: it goes into the class body, not into a method
      int lineEnd = area.getAbsolutePosition(paragraph, area.getParagraph(paragraph).length());
      var place = MemberPlacement.place(text, lineEnd, BlockDrop.eventMethod(snippet));
      if (place != null && place.existing()) {
        // the class already reacts to this event: go there instead of a second copy
        area.moveTo(place.offset());
        int open = area.getCurrentParagraph();
        if (open + 1 < area.getParagraphs().size()
            && !area.getParagraph(open + 1).getText().strip().startsWith("}")) {
          open++; // the method's first line
        }
        area.moveTo(open, area.getParagraph(open).length());
        area.requestFollowCaret();
        area.requestFocus();
        return false;
      }
      if (place != null) {
        paragraph = area.offsetToPosition(place.offset(),
            org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
      }
    }
    boolean blank = area.getParagraph(paragraph).getText().isBlank();
    BlockDrop.Target target = BlockDrop.at(text,
        new BlockDrop.Slot(blank ? paragraph : paragraph + 1, blank), snippet);
    if (!target.valid()) return refuseAtCaret(target);
    var insertion = target.insertion();
    area.replaceText(insertion.start(), insertion.end(), insertion.text());
    area.moveTo(insertion.caret());
    area.requestFollowCaret();
    area.requestFocus();
    return true;
  }

  /**
   * Adds {@code import} lines for these classes unless the file already has
   * them (or a wildcard import of their package); the caret stays in place.
   */
  void addImports(List<String> qualifiedNames) {
    for (String name : qualifiedNames) {
      var insertion = org.openpatch.scratch4j.core.project.JavaImports.insertion(
          area.getText(), name);
      if (insertion != null) {
        area.insertText(insertion.offset(), insertion.text());
      }
    }
  }

  /** The identifier at (or directly before) the caret. */
  String wordAtCaret() {
    int caret = area.getCaretPosition();
    int end = caret;
    String text = area.getText();
    while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
      end++;
    }
    Matcher m = IDENTIFIER.matcher(text.substring(0, end));
    return m.find() ? m.group() : "";
  }

  /** Moves the caret to (1-based) {@code line}, selects it and scrolls there. */
  void gotoLine(int line) {
    int paragraph = Math.max(0, Math.min(line - 1, area.getParagraphs().size() - 1));
    area.moveTo(paragraph, 0);
    area.selectRange(paragraph, 0, paragraph, area.getParagraphLength(paragraph));
    area.showParagraphAtCenter(paragraph);
    area.requestFocus();
  }

  /** Selects {@code start..end} (text offsets), scrolls there and focuses the editor. */
  void selectRange(int start, int end) {
    int length = area.getLength();
    int from = Math.max(0, Math.min(start, length));
    int to = Math.max(from, Math.min(end, length));
    area.selectRange(from, to);
    area.showParagraphAtCenter(area.getCurrentParagraph());
    area.requestFocus();
  }

  void focusEditor() {
    area.requestFocus();
  }

  void showFind(boolean replace) {
    findBar.open(replace);
  }

  /** Shows the given diagnostics as squiggles, gutter markers and hover text. */
  void setDiagnostics(List<Diagnostic> diagnostics) {
    this.diagnostics = List.copyOf(diagnostics);
    errorLines.clear();
    warningLines.clear();
    for (Diagnostic d : diagnostics) {
      (d.error() ? errorLines : warningLines).add((int) d.line() - 1);
    }
    rehighlight();
  }

  // --- highlighting ----------------------------------------------------------

  private void rehighlight() {
    // all styles are set anew: a link underline is gone with them
    link = null;
    linkStyles = null;
    String text = area.getText();
    StyleSpans<Collection<String>> spans = SyntaxHighlighter.highlight(text, language);
    diagnosticRanges.clear();
    if (!diagnostics.isEmpty()) {
      StyleSpansBuilder<Collection<String>> marks = new StyleSpansBuilder<>();
      List<int[]> ranges = new ArrayList<>();
      for (int i = 0; i < diagnostics.size(); i++) {
        int[] range = rangeOf(diagnostics.get(i));
        if (range != null) {
          ranges.add(new int[] {range[0], range[1], i});
        }
      }
      ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
      int last = 0;
      for (int[] range : ranges) {
        if (range[0] < last) {
          continue; // overlapping: the first one wins the squiggle
        }
        boolean error = diagnostics.get(range[2]).error();
        marks.add(List.of(), range[0] - last);
        marks.add(List.of(error ? "diag-error" : "diag-warning"), range[1] - range[0]);
        last = range[1];
        diagnosticRanges.add(range);
      }
      marks.add(List.of(), Math.max(0, text.length() - last));
      if (!ranges.isEmpty()) {
        spans = spans.overlay(marks.create(), (a, b) -> {
          if (b.isEmpty()) {
            return a;
          }
          List<String> merged = new ArrayList<>(a);
          merged.addAll(b);
          return merged;
        });
      }
    }
    bracketMarks.clear();
    if (text.isEmpty()) {
      return;
    }
    area.setStyleSpans(0, spans);
    markBrackets();
  }

  /** Character range a diagnostic underlines: the token at its column, or the line. */
  private int[] rangeOf(Diagnostic d) {
    int paragraph = (int) d.line() - 1;
    if (paragraph < 0 || paragraph >= area.getParagraphs().size()) {
      return null;
    }
    String line = area.getParagraph(paragraph).getText();
    int lineStart = area.getAbsolutePosition(paragraph, 0);
    if (line.isBlank()) {
      return null;
    }
    int col = (int) d.column() - 1;
    if (col >= 0 && col + 1 < line.length() && line.charAt(col) == '.'
        && Character.isJavaIdentifierStart(line.charAt(col + 1))) {
      col++; // javac points at the dot of a member select: underline the name
    }
    if (col < 0 || col >= line.length()) {
      int first = 0;
      while (first < line.length() && Character.isWhitespace(line.charAt(first))) {
        first++;
      }
      return new int[] {lineStart + first, lineStart + line.stripTrailing().length()};
    }
    int end = col;
    while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) {
      end++;
    }
    if (end == col) {
      end = Math.min(line.length(), col + 1);
    }
    return new int[] {lineStart + col, lineStart + end};
  }

  private void markBrackets() {
    for (int pos : bracketMarks) {
      if (pos < area.getLength()) {
        List<String> styles = new ArrayList<>(area.getStyleOfChar(pos));
        styles.remove("bracket-match");
        area.setStyle(pos, pos + 1, styles);
      }
    }
    bracketMarks.clear();
    String text = area.getText();
    int caret = area.getCaretPosition();
    int at = -1;
    if (caret > 0 && "(){}[]".indexOf(text.charAt(caret - 1)) >= 0) {
      at = caret - 1;
    } else if (caret < text.length() && "(){}[]".indexOf(text.charAt(caret)) >= 0) {
      at = caret;
    }
    if (at < 0) {
      return;
    }
    int match = matchingBracket(text, at);
    if (match < 0) {
      return;
    }
    for (int pos : new int[] {at, match}) {
      List<String> styles =
          new ArrayList<>(area.getStyleSpans(pos, pos + 1).getStyleSpan(0).getStyle());
      styles.add("bracket-match");
      area.setStyle(pos, pos + 1, styles);
      bracketMarks.add(pos);
    }
  }

  static int matchingBracket(String text, int at) {
    char c = text.charAt(at);
    String open = "({[";
    String close = ")}]";
    int direction = open.indexOf(c) >= 0 ? 1 : -1;
    char partner = direction == 1 ? close.charAt(open.indexOf(c)) : open.charAt(close.indexOf(c));
    int depth = 0;
    for (int i = at; i >= 0 && i < text.length(); i += direction) {
      char ch = text.charAt(i);
      if (ch == c) {
        depth++;
      } else if (ch == partner) {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  // --- gutter ----------------------------------------------------------------

  /** Breakpoint lines (1-based); a click on a line number toggles one. */
  private final ObservableSet<Integer> breakpoints = FXCollections.observableSet();
  private int debugLine = -1;

  ObservableSet<Integer> breakpoints() {
    return breakpoints;
  }

  /** Highlights the line the debugger paused at (1-based; -1 clears). */
  void showDebugLine(int line) {
    int old = debugLine;
    debugLine = line;
    paragraphStyle(old);
    if (line > 0 && line - 1 < area.getParagraphs().size()) {
      paragraphStyle(line);
      gotoLine(line);
    }
  }

  /** Lines (1-based) of the object selected in the visual panel beside the code. */
  private final java.util.Set<Integer> linkedLines = new java.util.HashSet<>();
  private java.util.function.Consumer<String> onCaretLine = line -> { };
  private int lastCaretParagraph = -1;

  /**
   * Highlights the lines that belong to the object chosen in the visual panel;
   * {@code reveal} scrolls the first one into view (without moving the caret).
   */
  void showLinkedLines(java.util.Collection<Integer> lines, boolean reveal) {
    java.util.Set<Integer> old = new java.util.HashSet<>(linkedLines);
    linkedLines.clear();
    linkedLines.addAll(lines);
    for (int line : old) paragraphStyle(line);
    for (int line : linkedLines) paragraphStyle(line);
    if (reveal && !linkedLines.isEmpty()) {
      int first = java.util.Collections.min(linkedLines) - 1;
      if (first >= 0 && first < area.getParagraphs().size()) {
        area.showParagraphInViewport(first);
      }
    }
  }

  java.util.Set<Integer> linkedLines() {
    return java.util.Set.copyOf(linkedLines);
  }

  /** Told the text of the caret's line whenever the caret moves to another line. */
  void setOnCaretLine(java.util.function.Consumer<String> listener) {
    this.onCaretLine = listener;
  }

  // --- the visual editors' blocks: tinted, named in the gutter ---------------------

  /** Line (0-based) -> style of the visual editors' blocks: marker, line, own code. */
  private final java.util.Map<Integer, String> regionStyles = new java.util.HashMap<>();
  /** Line (0-based) -> what the gutter says about it (the block's editor, own code). */
  private final javafx.collections.ObservableMap<Integer, String> regionNotes =
      javafx.collections.FXCollections.observableHashMap();

  /**
   * Finds the blocks a visual editor owns ({@code // begin stage designer}) and
   * marks them: the markers faint, the lines between tinted, and in a stage
   * the statements the designer leaves to the student's code.
   */
  private void updateRegions() {
    String text = area.getText();
    java.util.Map<Integer, String> styles = new java.util.HashMap<>();
    java.util.Map<Integer, String> notes = new java.util.HashMap<>();
    if (text.contains("// begin ") || text.contains("//begin ")) {
      try {
        int[] lineStarts = lineStarts(text);
        for (var region : org.openpatch.scratch4j.core.region.RegionParser.parse(text)) {
          int begin = lineAt(lineStarts, region.beginOffset()) - 1;
          int end = lineAt(lineStarts, region.endOffset());
          styles.put(begin, "region-marker");
          styles.put(end, "region-marker");
          for (int l = begin + 1; l < end; l++) styles.put(l, "region-line");
          notes.put(begin, I18n.t("code.region." + switch (region.editor()) {
            case org.openpatch.scratch4j.core.region.RegionParser.STAGE_DESIGNER -> "stage";
            case org.openpatch.scratch4j.core.region.RegionParser.SPRITE_EDITOR -> "sprite";
            default -> "settings";
          }));
        }
        if (text.contains("// begin stage designer")) {
          for (var own : org.openpatch.scratch4j.core.region.StageDocument.read(text).foreign()) {
            styles.put(own.line() - 1, "region-own");
            notes.put(own.line() - 1, I18n.t("code.region.own"));
          }
        }
      } catch (RuntimeException unfinished) {
        // markers that do not pair up yet, unfinished Java: no blocks to show
      }
    }
    java.util.Set<Integer> changed = new java.util.HashSet<>(regionStyles.keySet());
    changed.addAll(styles.keySet());
    regionStyles.clear();
    regionStyles.putAll(styles);
    regionNotes.keySet().retainAll(notes.keySet());
    regionNotes.putAll(notes);
    for (int line : changed) paragraphStyle(line + 1);
  }

  private static int[] lineStarts(String text) {
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') starts.add(i + 1);
    }
    return starts.stream().mapToInt(Integer::intValue).toArray();
  }

  /** The 0-based line an offset is on. */
  private static int lineAt(int[] lineStarts, int offset) {
    int found = java.util.Arrays.binarySearch(lineStarts, offset);
    return found >= 0 ? found : -found - 2;
  }

  /** The style of a line in a visual editor's block (tests); null outside the blocks. */
  String regionStyle(int line) {
    return regionStyles.get(line - 1);
  }

  private void paragraphStyle(int line) {
    if (line <= 0 || line - 1 >= area.getParagraphs().size()) return;
    List<String> styles = new ArrayList<>();
    String region = regionStyles.get(line - 1);
    if (region != null) styles.add(region);
    if (line == debugLine) styles.add("debug-line");
    if (linkedLines.contains(line)) styles.add("linked-line");
    if (line - 1 == dropGapLine) {
      styles.add((dropGapAbove ? "drop-gap-above-" : "drop-gap-below-") + dropGapRows);
    }
    area.setParagraphStyle(line - 1, styles);
  }

  private java.util.function.Consumer<Path> onOpenFile = file -> { };

  /** A whole line replaced (a colour picked in the gutter), as one undo step. */
  private void replaceParagraph(int paragraph, String text) {
    if (paragraph >= area.getParagraphs().size()) return;
    int start = area.getAbsolutePosition(paragraph, 0);
    int end = start + area.getParagraph(paragraph).length();
    if (!area.getText(start, end).equals(text)) {
      area.replaceText(start, end, text);
    }
  }

  /** Opens a project file a gutter preview points at (paint, sound, map editor). */
  void setOnOpenFile(java.util.function.Consumer<Path> action) {
    this.onOpenFile = action;
  }

  /** Lines (1-based) with a fix, and its label: a light bulb in the gutter runs it. */
  private final javafx.collections.ObservableMap<Integer, String> fixes =
      javafx.collections.FXCollections.observableHashMap();
  private java.util.function.IntConsumer onFix = line -> { };

  void setFixes(Map<Integer, String> byLine) {
    fixes.clear();
    fixes.putAll(byLine);
  }

  void setOnFix(java.util.function.IntConsumer action) {
    this.onFix = action;
  }

  Map<Integer, String> fixes() {
    return Map.copyOf(fixes);
  }

  private IntFunction<Node> gutter() {
    IntFunction<Node> numbers = LineNumberFactory.get(area);
    return line -> {
      Node number = numbers.apply(line);
      Label bulb = new Label(null, Icons.of("fth-zap", 12));
      bulb.getStyleClass().add("gutter-fix");
      bulb.setMinWidth(14);
      Runnable updateFix = () -> {
        String label = fixes.get(line + 1);
        bulb.setVisible(label != null);
        bulb.setTooltip(label == null ? null : new Tooltip(label));
      };
      updateFix.run();
      fixes.addListener((javafx.collections.MapChangeListener<Integer, String>) c ->
          updateFix.run());
      bulb.setOnMouseClicked(e -> {
        if (fixes.containsKey(line + 1)) {
          onFix.accept(line + 1);
          e.consume();
        }
      });
      // a visual editor's block starts here, or the designer leaves this line be
      Label block = new Label();
      block.getStyleClass().add("gutter-region");
      block.setMinWidth(14);
      Runnable updateBlock = () -> {
        String note = regionNotes.get(line);
        block.setGraphic(note == null ? null : Icons.of(
            "region-own".equals(regionStyles.get(line)) ? "fth-code" : "fth-layout", 12));
        block.setTooltip(note == null ? null : new Tooltip(note));
      };
      updateBlock.run();
      regionNotes.addListener((javafx.collections.MapChangeListener<Integer, String>) c ->
          updateBlock.run());
      Region marker = new Region();
      marker.getStyleClass().add("gutter-marker");
      Runnable update = () -> {
        marker.getStyleClass().removeAll("error", "warning", "breakpoint");
        if (breakpoints.contains(line + 1)) {
          marker.getStyleClass().add("breakpoint");
        } else if (errorLines.contains(line)) {
          marker.getStyleClass().add("error");
        } else if (warningLines.contains(line)) {
          marker.getStyleClass().add("warning");
        }
      };
      update.run();
      errorLines.addListener((javafx.collections.SetChangeListener<Integer>) c -> update.run());
      warningLines.addListener(
          (javafx.collections.SetChangeListener<Integer>) c -> update.run());
      breakpoints.addListener((javafx.collections.SetChangeListener<Integer>) c -> update.run());
      // a preview of the image, sound or map the line uses
      Node preview = line < area.getParagraphs().size()
          ? CodePreviews.forLine(area.getParagraph(line).getText(), projectRoot.get(),
              onOpenFile, text -> replaceParagraph(line, text))
          : null;
      if (preview == null && line < area.getParagraphs().size()) {
        // what the class's costume looks like through the shader the line adds
        preview = ShaderPreviews.forLine(area.getParagraph(line).getText(), line,
            projectRoot.get(), file, area::getText, onOpenFile);
      }
      StackPane previewSlot = new StackPane();
      previewSlot.setMinWidth(18);
      previewSlot.setPrefWidth(18);
      if (preview != null) previewSlot.getChildren().add(preview);
      HBox box = new HBox(marker, bulb, number, block, previewSlot);
      box.setAlignment(Pos.CENTER_LEFT);
      box.getStyleClass().add("gutter");
      box.setCursor(javafx.scene.Cursor.HAND);
      box.setOnMouseClicked(e -> {
        if (!breakpoints.remove(line + 1)) {
          breakpoints.add(line + 1);
        }
        e.consume();
      });
      if (line != dropGapLine) return box;
      // a dragged block's preview opens lines here: the gutter stays beside the line's text
      Region gap = new Region();
      gap.getStyleClass().add("gutter");
      double height = dropGapRows * gapStyleRow;
      gap.setMinHeight(height);
      gap.setPrefHeight(height);
      gap.setMaxHeight(height);
      box.setMaxHeight(Double.MAX_VALUE);
      VBox.setVgrow(box, Priority.ALWAYS);
      return dropGapAbove ? new VBox(gap, box) : new VBox(box, gap);
    };
  }

  // --- keys --------------------------------------------------------------------

  private void installKeys() {
    area.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (completionPopup.isShowing() && handleCompletionKey(e)) {
        e.consume();
        return;
      }
      boolean shortcut = e.isShortcutDown();
      if (shortcut && e.getCode() == KeyCode.SPACE) {
        showCompletion(true);
        e.consume();
      } else if (e.getCode() == KeyCode.ENTER && !shortcut) {
        newlineWithIndent();
        e.consume();
      } else if (e.getCode() == KeyCode.TAB && !shortcut) {
        if (e.isShiftDown()) {
          shiftSelection(false);
        } else if (area.getSelection().getLength() > 0
            && area.getSelectedText().contains("\n")) {
          shiftSelection(true);
        } else {
          area.replaceSelection(INDENT);
        }
        e.consume();
      } else if (shortcut && (e.getCode() == KeyCode.SLASH || e.getCode() == KeyCode.DIVIDE
          || e.getCode() == KeyCode.NUMBER_SIGN)) {
        toggleComment();
        e.consume();
      } else if (shortcut && (e.getCode() == KeyCode.PLUS || e.getCode() == KeyCode.EQUALS
          || e.getCode() == KeyCode.ADD)) {
        zoom(1);
        e.consume();
      } else if (shortcut && (e.getCode() == KeyCode.MINUS
          || e.getCode() == KeyCode.SUBTRACT)) {
        zoom(-1);
        e.consume();
      } else if (shortcut && (e.getCode() == KeyCode.DIGIT0 || e.getCode() == KeyCode.NUMPAD0)) {
        FONT_SIZE.set(14);
        e.consume();
      } else if (e.getCode() == KeyCode.F12 && e.isShiftDown()) {
        onFindUsages.accept(area.getCaretPosition());
        e.consume();
      } else if (e.getCode() == KeyCode.F12) {
        onGoToDefinition.accept(area.getCaretPosition());
        e.consume();
      } else if (e.getCode() == KeyCode.F2 && !e.isShiftDown()) {
        onRename.accept(area.getCaretPosition());
        e.consume();
      } else if (shortcut && e.isShiftDown() && e.getCode() == KeyCode.H) {
        onFindInProject.accept(searchStart());
        e.consume();
      } else if (shortcut && !e.isShiftDown() && e.getCode() == KeyCode.D) {
        duplicateLines();
        e.consume();
      } else if (shortcut && e.isShiftDown() && e.getCode() == KeyCode.K) {
        deleteLines();
        e.consume();
      } else if (e.isAltDown() && !shortcut
          && (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN)) {
        moveLines(e.getCode() == KeyCode.UP ? -1 : 1);
        e.consume();
      } else if (shortcut && !e.isShiftDown() && e.getCode() == KeyCode.G) {
        askGotoLine();
        e.consume();
      } else if (shortcut && e.isShiftDown() && e.getCode() == KeyCode.F) {
        formatIndentation();
        e.consume();
      } else if (shortcut && e.getCode() == KeyCode.F) {
        findBar.open(false);
        e.consume();
      } else if (shortcut && (e.getCode() == KeyCode.H || e.getCode() == KeyCode.R)) {
        findBar.open(true);
        e.consume();
      }
    });
    area.addEventFilter(KeyEvent.KEY_TYPED, e -> {
      String ch = e.getCharacter();
      if ("}".equals(ch)) {
        dedentBeforeCaret();
      }
    });
    area.addEventHandler(KeyEvent.KEY_TYPED, e -> {
      String ch = e.getCharacter();
      if (ch.isEmpty() || e.isShortcutDown()) {
        return;
      }
      if (completionPopup.isShowing()) {
        Platform.runLater(() -> showCompletion(false));
      } else if (".".equals(ch) || "\"".equals(ch) && insideAssetString()) {
        Platform.runLater(() -> showCompletion(false));
      } else if (Character.isJavaIdentifierPart(ch.charAt(0)) && typingWord()) {
        // suggestions while typing a word (2+ letters), not in comments or plain strings
        Platform.runLater(() -> showCompletion(false));
      }
    });
    area.addEventFilter(ScrollEvent.SCROLL, e -> {
      if (e.isShortcutDown()) {
        zoom(e.getDeltaY() > 0 ? 1 : -1);
        e.consume();
      }
    });
  }

  private static void zoom(int delta) {
    FONT_SIZE.set(Math.max(9, Math.min(36, FONT_SIZE.get() + delta)));
  }

  private String currentIndent() {
    String line = area.getParagraph(area.getCurrentParagraph()).getText();
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return line.substring(0, i);
  }

  private void newlineWithIndent() {
    String text = area.getText();
    int caret = area.getSelection().getStart();
    String indent = currentIndent();
    char before = previousNonSpace(text, caret);
    char after = caret < text.length() ? text.charAt(caret) : 0;
    if (before == '{' && after == '}') {
      area.replaceSelection("\n" + indent + INDENT + "\n" + indent);
      area.moveTo(caret + 1 + indent.length() + INDENT.length());
    } else if (before == '{') {
      area.replaceSelection("\n" + indent + INDENT);
    } else {
      area.replaceSelection("\n" + indent);
    }
    area.requestFollowCaret();
  }

  private static char previousNonSpace(String text, int pos) {
    for (int i = pos - 1; i >= 0; i--) {
      char c = text.charAt(i);
      if (c == '\n') {
        return 0;
      }
      if (!Character.isWhitespace(c)) {
        return c;
      }
    }
    return 0;
  }

  /** Typing {@code }} on a whitespace-only line removes one indent level. */
  private void dedentBeforeCaret() {
    int paragraph = area.getCurrentParagraph();
    int column = area.getCaretColumn();
    String beforeCaret = area.getParagraph(paragraph).getText().substring(0, column);
    if (!beforeCaret.isBlank() || beforeCaret.length() < INDENT.length()) {
      return;
    }
    int start = area.getAbsolutePosition(paragraph, 0);
    area.deleteText(start, start + INDENT.length());
  }

  private void shiftSelection(boolean right) {
    int first = area.offsetToPosition(area.getSelection().getStart(),
        org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
    int last = area.offsetToPosition(area.getSelection().getEnd(),
        org.fxmisc.richtext.model.TwoDimensional.Bias.Backward).getMajor();
    for (int p = first; p <= last; p++) {
      int start = area.getAbsolutePosition(p, 0);
      if (right) {
        area.insertText(start, INDENT);
      } else if (area.getParagraph(p).getText().startsWith(INDENT)) {
        area.deleteText(start, start + INDENT.length());
      }
    }
    area.selectRange(first, 0, last, area.getParagraphLength(last));
  }

  private void toggleComment() {
    int first = area.offsetToPosition(area.getSelection().getStart(),
        org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
    int last = area.offsetToPosition(area.getSelection().getEnd(),
        org.fxmisc.richtext.model.TwoDimensional.Bias.Backward).getMajor();
    boolean allCommented = true;
    for (int p = first; p <= last; p++) {
      String line = area.getParagraph(p).getText();
      if (!line.isBlank() && !line.stripLeading().startsWith("//")) {
        allCommented = false;
      }
    }
    for (int p = first; p <= last; p++) {
      String line = area.getParagraph(p).getText();
      if (line.isBlank()) {
        continue;
      }
      int indent = line.length() - line.stripLeading().length();
      int start = area.getAbsolutePosition(p, indent);
      if (allCommented) {
        int len = line.stripLeading().startsWith("// ") ? 3 : 2;
        area.deleteText(start, start + len);
      } else {
        area.insertText(start, "// ");
      }
    }
  }

  // --- line editing ---------------------------------------------------------------

  /** The first and last paragraph the selection (or the caret) touches. */
  private int[] selectedLines() {
    int first = area.offsetToPosition(area.getSelection().getStart(),
        org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
    int last = area.offsetToPosition(area.getSelection().getEnd(),
        org.fxmisc.richtext.model.TwoDimensional.Bias.Backward).getMajor();
    // a selection that ends at the start of a line does not include that line
    if (last > first && area.getSelection().getLength() > 0
        && area.getSelection().getEnd() == area.getAbsolutePosition(last, 0)) {
      last--;
    }
    return new int[] {first, last};
  }

  /** Ctrl+D: copies the caret's line (or the selected lines) below itself. */
  void duplicateLines() {
    int[] lines = selectedLines();
    int start = area.getAbsolutePosition(lines[0], 0);
    int end = area.getAbsolutePosition(lines[1], area.getParagraphLength(lines[1]));
    String block = area.getText(start, end);
    int caret = area.getCaretPosition();
    var selection = area.getSelection();
    area.insertText(end, "\n" + block);
    int shift = block.length() + 1;
    if (selection.getLength() > 0) {
      area.selectRange(selection.getStart() + shift, selection.getEnd() + shift);
    } else {
      area.moveTo(caret + shift);
    }
    area.requestFollowCaret();
  }

  /** Ctrl+Shift+K: removes the caret's line (or the selected lines). */
  void deleteLines() {
    int[] lines = selectedLines();
    int paragraphs = area.getParagraphs().size();
    int column = area.getCaretColumn();
    int start = area.getAbsolutePosition(lines[0], 0);
    int end = area.getAbsolutePosition(lines[1], area.getParagraphLength(lines[1]));
    if (lines[1] < paragraphs - 1) {
      end++; // with its line break
    } else if (lines[0] > 0) {
      start--; // the last line: take the break before it
    }
    area.deleteText(start, end);
    int line = Math.min(lines[0], area.getParagraphs().size() - 1);
    area.moveTo(line, Math.min(column, area.getParagraphLength(line)));
    area.requestFollowCaret();
  }

  /** Alt+Up / Alt+Down: swaps the selected lines with the line above or below. */
  void moveLines(int direction) {
    int[] lines = selectedLines();
    int paragraphs = area.getParagraphs().size();
    if (direction < 0 && lines[0] == 0 || direction > 0 && lines[1] >= paragraphs - 1) {
      return;
    }
    int anchor = area.getAnchor();
    int caret = area.getCaretPosition();
    int start = area.getAbsolutePosition(lines[0], 0);
    int end = area.getAbsolutePosition(lines[1], area.getParagraphLength(lines[1]));
    String block = area.getText(start, end);
    int shift;
    if (direction < 0) {
      int above = area.getAbsolutePosition(lines[0] - 1, 0);
      String line = area.getText(above, start - 1);
      area.replaceText(above, end, block + "\n" + line);
      shift = -(line.length() + 1);
    } else {
      int belowEnd = area.getAbsolutePosition(lines[1] + 1,
          area.getParagraphLength(lines[1] + 1));
      String line = area.getText(end + 1, belowEnd);
      area.replaceText(start, belowEnd, line + "\n" + block);
      shift = line.length() + 1;
    }
    area.selectRange(anchor + shift, caret + shift);
    area.requestFollowCaret();
  }

  /** Ctrl+G: asks for a line number and jumps there. */
  void askGotoLine() {
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog(String.valueOf(caretLine()));
    dialog.setTitle(I18n.t("editor.gotoline"));
    dialog.setHeaderText(null);
    dialog.setContentText(I18n.t("editor.gotoline.prompt", area.getParagraphs().size()));
    Theme.style(dialog);
    dialog.showAndWait().map(String::trim).ifPresent(answer -> {
      try {
        gotoLine(Integer.parseInt(answer));
      } catch (NumberFormatException ignored) {
        // not a number: stay where we are
      }
    });
  }

  /** What a project search starts with: the selection (one line) or the word at the caret. */
  String searchStart() {
    String selected = area.getSelectedText();
    if (!selected.isEmpty() && !selected.contains("\n")) {
      return selected;
    }
    return wordAtCaret();
  }

  /** Re-indents the whole file by brace depth (Ctrl+Shift+F). */
  /**
   * Edit > Format: Java gets the full formatter (spacing, braces, indentation;
   * only whitespace changes), other text files the brace re-indent.
   */
  void formatIndentation() {
    if (file.getFileName().toString().endsWith(".java")) {
      String before = area.getText();
      String formatted = org.openpatch.scratch4j.core.lint.JavaFormatter.format(before);
      if (!before.endsWith("\n") && formatted.endsWith("\n")) {
        formatted = formatted.substring(0, formatted.length() - 1);
      }
      if (!formatted.equals(before)) {
        int caret = area.getCurrentParagraph();
        area.replaceText(formatted);
        area.moveTo(Math.min(caret, area.getParagraphs().size() - 1), 0);
      }
      return;
    }
    String[] lines = area.getText().split("\n", -1);
    StringBuilder out = new StringBuilder();
    int depth = 0;
    boolean inBlockComment = false;
    for (int i = 0; i < lines.length; i++) {
      String trimmed = lines[i].strip();
      int lineDepth = depth;
      if (!inBlockComment && (trimmed.startsWith("}") || trimmed.startsWith(")"))) {
        lineDepth = Math.max(0, depth - 1);
      }
      String prefix = inBlockComment && trimmed.startsWith("*") ? " " : "";
      out.append(trimmed.isEmpty() ? "" : INDENT.repeat(lineDepth) + prefix + trimmed);
      if (i < lines.length - 1) {
        out.append('\n');
      }
      depth = Math.max(0, depth + braceDelta(trimmed, inBlockComment));
      if (trimmed.contains("/*") && !trimmed.contains("*/")) {
        inBlockComment = true;
      } else if (trimmed.contains("*/")) {
        inBlockComment = false;
      }
    }
    int caret = area.getCurrentParagraph();
    area.replaceText(out.toString());
    area.moveTo(Math.min(caret, area.getParagraphs().size() - 1), 0);
  }

  private static int braceDelta(String line, boolean inBlockComment) {
    if (inBlockComment) {
      return 0;
    }
    int delta = 0;
    boolean inString = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
        inString = !inString;
      } else if (!inString && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
        break;
      } else if (!inString && c == '{') {
        delta++;
      } else if (!inString && c == '}') {
        delta--;
      }
    }
    return delta;
  }

  // --- blocks dragged in ----------------------------------------------------------

  /** Most lines a preview opens at once (a hat block with blank lines around it is five). */
  private static final int MAX_GAP_ROWS = 12;
  /** How far a preview's colour reaches left of its code. */
  private static final double GHOST_INSET = 6;

  /** The previews of blocks dragged over the code: their lines, a caret, or a note. */
  private final javafx.scene.layout.Pane dropLayer = new javafx.scene.layout.Pane();
  /** The drop the preview shows, for the block {@link #dropSnippet}; null without one. */
  private BlockDrop.Target dropTarget;
  private String dropSnippet;
  /** The line the preview opens lines at (-1: none), above or below it, and how many. */
  private int dropGapLine = -1;
  private boolean dropGapAbove;
  private int dropGapRows;
  /** The line height the gap sizes in {@link #gapStyles} are made for. */
  private double gapStyleRow = -1;
  private String gapStyles;
  private java.util.function.Consumer<String> onBlockDropped = snippet -> { };

  /** Told about every block dropped in from the palette (it adds the imports it needs). */
  void setOnBlockDropped(java.util.function.Consumer<String> action) {
    this.onBlockDropped = action;
  }

  /**
   * Blocks dragged from the palette: a stack block opens a new line where it
   * would land, with its code in it, inside a method or constructor; a hat
   * block opens lines between two members of the class; a reporter shows a
   * caret in the line. Where a block can't go, a note says why and the drop is
   * refused. Other dragged text drops in at the pointer.
   */
  private void installBlockDrop() {
    dropLayer.setMouseTransparent(true);
    dropLayer.styleProperty().bind(FONT_SIZE.asString("-fx-font-size: %dpx;"));
    javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
    clip.widthProperty().bind(dropLayer.widthProperty());
    clip.heightProperty().bind(dropLayer.heightProperty());
    dropLayer.setClip(clip);
    area.setOnDragOver(e -> {
      var board = e.getDragboard();
      if (e.getGestureSource() != area && board.hasString()) {
        e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
        area.moveTo(area.hit(e.getX(), e.getY()).getInsertionIndex());
      }
      e.consume();
    });
    area.setOnDragDropped(e -> {
      var board = e.getDragboard();
      boolean done = false;
      if (board.hasString()) {
        int index = area.hit(e.getX(), e.getY()).getInsertionIndex();
        area.insertText(index, board.getString());
        area.moveTo(index + board.getString().length());
        done = true;
      }
      e.setDropCompleted(done);
      e.consume();
    });
    caretNoteTimer.setOnFinished(e -> hideCaretNote());
    area.caretPositionProperty().addListener((o, old, now) -> hideCaretNote());
    area.textProperty().addListener((o, old, now) -> hideCaretNote());
    area.addEventHandler(KeyEvent.KEY_PRESSED, e -> hideCaretNote());
  }

  // the palette's drag: the block follows the pointer, over the code it shows where it goes

  private Node dragChip;
  private double grabX;
  private double grabY;

  @Override
  public void startBlockDrag(Node chip, double grabX, double grabY) {
    this.grabX = grabX;
    this.grabY = grabY;
    dragChip = chip;
    chip.getStyleClass().add("block-drag-chip");
    dragLayer.getChildren().setAll(chip);
    dragLayer.resizeRelocate(0, 0, codeHolder.getWidth(), codeHolder.getHeight());
    setCursor(javafx.scene.Cursor.CLOSED_HAND);
  }

  @Override
  public void moveBlockDrag(String snippet, String category, double screenX, double screenY) {
    if (dragChip != null) {
      javafx.geometry.Point2D at = dragLayer.screenToLocal(screenX, screenY);
      dragChip.relocate(at.getX() - grabX, at.getY() - grabY);
    }
    if (overCode(screenX, screenY)) {
      BlockDrop.Target target = previewBlock(snippet, category, screenX, screenY);
      // the preview in the code is what counts: the block itself fades over it
      if (dragChip != null) dragChip.setOpacity(target.valid() ? 0.35 : 0.85);
    } else {
      if (dropTarget != null || !dropLayer.getChildren().isEmpty()) clearDrop();
      if (dragChip != null) dragChip.setOpacity(0.85);
    }
  }

  @Override
  public void endBlockDrag(String snippet, double screenX, double screenY) {
    endDragChip();
    if (overCode(screenX, screenY)) {
      dropBlock(snippet, screenX, screenY);
    } else {
      clearDrop();
    }
  }

  private void endDragChip() {
    dragChip = null;
    dragLayer.getChildren().clear();
    setCursor(null);
  }

  /** Whether a screen point is over the code (not the palette, not outside the editor). */
  private boolean overCode(double screenX, double screenY) {
    javafx.geometry.Bounds code = codeColumn.localToScreen(codeColumn.getLayoutBounds());
    return code != null && code.contains(screenX, screenY);
  }

  /**
   * A palette block ({@code category}: its colour's style class) dragged over
   * the code at a screen point: the preview shows where it would go.
   */
  BlockDrop.Target previewBlock(String snippet, String category, double screenX,
      double screenY) {
    BlockDrop.Target target = blockTarget(snippet, screenX, screenY);
    if (target.valid() && target.equals(dropTarget) && snippet.equals(dropSnippet)
        && !dropLayer.getChildren().isEmpty()) {
      return target; // the same place: the preview stays as it is
    }
    dropTarget = target;
    dropSnippet = snippet;
    caretNote = null;
    dropLayer.getChildren().clear();
    if (!target.valid()) {
      setDropGap(-1, false, 0);
      showDropNote(target, screenX, screenY);
    } else if (target.kind() == BlockDrop.Kind.EXPRESSION) {
      setDropGap(-1, false, 0);
      showDropValue(target.insertion().start(), snippet, category);
    } else {
      showDropLines(target, category);
    }
    return target;
  }

  /** A palette block dropped at a screen point: it goes where the preview showed, if it may. */
  boolean dropBlock(String snippet, double screenX, double screenY) {
    BlockDrop.Target target = blockTarget(snippet, screenX, screenY);
    clearDrop();
    if (!target.valid()) return false;
    var insertion = target.insertion();
    area.replaceText(insertion.start(), insertion.end(), insertion.text());
    area.moveTo(insertion.caret());
    area.requestFollowCaret();
    area.requestFocus();
    onBlockDropped.accept(snippet);
    return true;
  }

  /** No preview any more: the lines it opened close. */
  void clearDrop() {
    dropTarget = null;
    dropSnippet = null;
    caretNote = null;
    dropLayer.getChildren().clear();
    setDropGap(-1, false, 0);
  }

  /** The preview's layer (tests). */
  javafx.scene.layout.Pane dropLayer() {
    return dropLayer;
  }

  /** Where a block under the pointer goes; screen coordinates. */
  private BlockDrop.Target blockTarget(String snippet, double screenX, double screenY) {
    String text = area.getText();
    if (BlockDrop.kindOf(snippet) == BlockDrop.Kind.EXPRESSION) {
      javafx.geometry.Point2D local = area.screenToLocal(screenX, screenY);
      int offset = area.hit(local.getX(), local.getY()).getInsertionIndex();
      return BlockDrop.inline(text, offset, snippet);
    }
    LineHit hit = lineAt(screenY);
    if (hit == null) return BlockDrop.onLine(text, 0, true, snippet);
    if (hit.inGap() && dropTarget != null && snippet.equals(dropSnippet)) {
      return dropTarget; // over the lines the preview opened: it stays
    }
    return BlockDrop.onLine(text, hit.line(), hit.upper(), snippet);
  }

  /** The line at a screen height, whether in its upper half, or in the lines a preview opened. */
  private record LineHit(int line, boolean upper, boolean inGap) { }

  private LineHit lineAt(double screenY) {
    int visible = area.getVisibleParagraphs().size();
    double row = rowHeight();
    LineHit below = null;
    for (int i = 0; i < visible; i++) {
      int line = area.visibleParToAllParIndex(i);
      javafx.geometry.Bounds bounds = area.getVisibleParagraphBoundsOnScreen(i);
      if (i == 0 && screenY < bounds.getMinY()) return new LineHit(line, true, false);
      if (screenY < bounds.getMaxY()) {
        double gap = line == dropGapLine ? openGap(bounds, row) : 0;
        double top = bounds.getMinY() + (dropGapAbove ? gap : 0);
        double bottom = bounds.getMaxY() - (dropGapAbove ? 0 : gap);
        if (screenY < top || screenY >= bottom) return new LineHit(line, false, true);
        return new LineHit(line, screenY < (top + bottom) / 2, false);
      }
      below = new LineHit(line, false, false);
    }
    return below;
  }

  /** The height of one line on screen, measured on the lines shown. */
  private double rowHeight() {
    double row = Double.MAX_VALUE;
    for (int i = 0; i < area.getVisibleParagraphs().size(); i++) {
      if (area.visibleParToAllParIndex(i) == dropGapLine) continue;
      row = Math.min(row, area.getVisibleParagraphBoundsOnScreen(i).getHeight());
    }
    if (row == Double.MAX_VALUE) return Math.ceil(FONT_SIZE.get() * 1.2);
    return Math.round(row * 100) / 100.0;
  }

  /** The height of the lines a preview opened at a line with these bounds (0: none laid out). */
  private static double openGap(javafx.geometry.Bounds bounds, double row) {
    double extra = bounds.getHeight() - row;
    return extra > row / 2 ? extra : 0;
  }

  /** Where the top of {@code line} would be on screen without the preview's lines (NaN: hidden). */
  private double topWithoutGap(int line, double row) {
    var visible = area.allParToVisibleParIndex(line);
    if (visible.isPresent()) {
      return area.getVisibleParagraphBoundsOnScreen(visible.get()).getMinY() - gapAbove(line, row);
    }
    var previous = line > 0 ? area.allParToVisibleParIndex(line - 1)
        : java.util.Optional.<Integer>empty();
    if (previous.isPresent()) {
      return area.getVisibleParagraphBoundsOnScreen(previous.get()).getMinY()
          - gapAbove(line - 1, row) + row;
    }
    return Double.NaN;
  }

  /** How far the lines the preview opened push the top of {@code line} down. */
  private double gapAbove(int line, double row) {
    if (dropGapLine < 0 || dropGapLine >= line) return 0;
    return area.allParToVisibleParIndex(dropGapLine)
        .map(i -> openGap(area.getVisibleParagraphBoundsOnScreen(i), row))
        .orElse(0.0);
  }

  /** A stack or hat block: the lines it adds open up, with its code in them. */
  private void showDropLines(BlockDrop.Target target, String category) {
    var slot = target.slot();
    var insertion = target.insertion();
    // measured on the layout shown now, before the lines open somewhere else
    double row = rowHeight();
    double top = topWithoutGap(slot.line(), row);
    double[] column = textColumn();
    ensureGapStyles(row);
    int rows = Math.min(insertion.addedRows(), MAX_GAP_ROWS);
    if (slot.fill()) {
      setDropGap(slot.line(), false, rows);
    } else if (slot.line() > 0 && area.allParToVisibleParIndex(slot.line() - 1).isPresent()) {
      setDropGap(slot.line() - 1, false, rows);
    } else {
      setDropGap(slot.line(), true, rows);
    }
    if (Double.isNaN(top)) return;

    VBox lines = new VBox();
    for (String code : insertion.code()) {
      Label line = new Label(code);
      line.getStyleClass().add("drop-ghost-code");
      line.setMinHeight(row);
      line.setPrefHeight(row);
      line.setMaxHeight(row);
      lines.getChildren().add(line);
    }
    // the code at a fixed distance from the ghost's left edge, the colour bar on that edge
    Region tint = new Region();
    tint.getStyleClass().addAll("drop-ghost-tint", category);
    tint.setManaged(false);
    Region bar = new Region();
    bar.getStyleClass().addAll("drop-ghost-bar", category);
    bar.setManaged(false);
    StackPane ghost = new StackPane(tint, bar, lines);
    ghost.setAlignment(Pos.TOP_LEFT);
    ghost.setPadding(new javafx.geometry.Insets(0, 0, 0, GHOST_INSET));
    dropLayer.getChildren().add(ghost);
    ghost.applyCss();
    ghost.autosize();
    tint.resizeRelocate(0, 0, ghost.getWidth(), ghost.getHeight());
    bar.resizeRelocate(0, 0, 3, ghost.getHeight());
    int columns = insertion.indent().replace("\t", " ".repeat(8)).length();
    javafx.geometry.Point2D at = dropLayer.screenToLocal(
        column[0] + columns * column[1], top + insertion.blankBefore() * row);
    ghost.relocate(at.getX() - GHOST_INSET, at.getY());
  }

  /** A reporter: its code in the line where it would go, a caret at its start. */
  private void showDropValue(int offset, String snippet, String category) {
    javafx.geometry.Bounds line = caretOnScreen(offset);
    if (line == null) return;
    Label code = new Label(snippet);
    code.getStyleClass().addAll("drop-ghost-code", "drop-ghost-value");
    code.setMinHeight(line.getHeight());
    code.setPrefHeight(line.getHeight());
    code.setMaxHeight(line.getHeight());
    Region tint = new Region();
    tint.getStyleClass().addAll("drop-ghost-value-tint", category);
    tint.setManaged(false);
    Region caret = new Region();
    caret.getStyleClass().add("drop-caret");
    caret.setManaged(false);
    StackPane ghost = new StackPane(tint, code, caret);
    ghost.getStyleClass().add("drop-ghost-value-box");
    ghost.setAlignment(Pos.TOP_LEFT);
    dropLayer.getChildren().add(ghost);
    ghost.applyCss();
    ghost.autosize();
    // over the text after it, as if the text had moved aside
    tint.resizeRelocate(0, 0, ghost.getWidth(), ghost.getHeight());
    caret.applyCss();
    caret.resizeRelocate(-1, 0, Math.max(2, caret.prefWidth(-1)), ghost.getHeight());
    javafx.geometry.Point2D at = dropLayer.screenToLocal(line.getMinX(), line.getMinY());
    ghost.relocate(at.getX(), at.getY());
  }

  /** Where a caret at {@code offset} stands on screen, as tall as its line; null: not shown. */
  private javafx.geometry.Bounds caretOnScreen(int offset) {
    String text = area.getText();
    double x;
    if (offset < text.length() && text.charAt(offset) != '\n') {
      x = area.getCharacterBoundsOnScreen(offset, offset + 1)
          .map(javafx.geometry.Bounds::getMinX).orElse(Double.NaN);
    } else if (offset > 0 && text.charAt(offset - 1) != '\n') {
      // a character's bounds reach a little past it: its right end is where the next one starts
      x = area.getCharacterBoundsOnScreen(offset - 1, offset)
          .map(b -> b.getMinX() + textColumn()[1]).orElse(Double.NaN);
    } else {
      x = textColumn()[0];
    }
    var visible = area.allParToVisibleParIndex(area.offsetToPosition(offset,
        org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor());
    if (Double.isNaN(x) || visible.isEmpty()) return null;
    javafx.geometry.Bounds line = area.getVisibleParagraphBoundsOnScreen(visible.get());
    return new javafx.geometry.BoundingBox(x, line.getMinY(), 0, line.getHeight());
  }

  /** A block that can't go where the caret is: a note under the caret says why (for a while). */
  private boolean refuseAtCaret(BlockDrop.Target target) {
    String message = switch (target.problem()) {
      case BODY -> I18n.t("caret.body");
      case STATEMENT -> I18n.t("caret.statement");
      case CLASS_BODY -> I18n.t("caret.classbody");
      case MEMBER -> I18n.t("caret.member");
      case EXISTS -> I18n.t("drop.exists", String.valueOf(target.existingLine()));
      case TEXT -> I18n.t("caret.text");
    };
    area.requestFocus();
    area.requestFollowCaret();
    area.layout();
    hideCaretNote();
    boolean onScreen = getScene() != null && getScene().getWindow() != null;
    javafx.geometry.Bounds caret = onScreen ? caretOnScreen(area.getSelection().getStart()) : null;
    // under the caret, else (scrolled away) at the top of the code
    javafx.geometry.Point2D at = caret == null ? new javafx.geometry.Point2D(60, 8)
        : dropLayer.screenToLocal(caret.getMinX(), caret.getMaxY() + 4);
    caretNote = showNote(message, at);
    caretNoteTimer.playFromStart();
    return false;
  }

  /** The note a refused insertion left at the caret: gone after a while or the next edit. */
  private Label caretNote;
  private final javafx.animation.PauseTransition caretNoteTimer =
      new javafx.animation.PauseTransition(javafx.util.Duration.seconds(6));

  private void hideCaretNote() {
    if (caretNote != null) dropLayer.getChildren().remove(caretNote);
    caretNote = null;
  }

  /** The note a refused insertion shows at the caret, if any (tests). */
  String caretNoteText() {
    return caretNote == null ? null : caretNote.getText();
  }

  /** Where a block can't go: a note beside the pointer says why. */
  private void showDropNote(BlockDrop.Target target, double screenX, double screenY) {
    String message = switch (target.problem()) {
      case BODY -> I18n.t("drop.body");
      case STATEMENT -> I18n.t("drop.statement");
      case CLASS_BODY -> I18n.t("drop.classbody");
      case MEMBER -> I18n.t("drop.member");
      case EXISTS -> I18n.t("drop.exists", String.valueOf(target.existingLine()));
      case TEXT -> I18n.t("drop.text");
    };
    showNote(message, dropLayer.screenToLocal(screenX + 16, screenY + 20));
  }

  /** A note with its top left corner at {@code at} (in the layer), kept inside the code's view. */
  private Label showNote(String message, javafx.geometry.Point2D at) {
    Label note = new Label(message);
    note.getStyleClass().add("drop-note");
    note.setWrapText(true);
    note.setMaxWidth(320);
    dropLayer.getChildren().add(note);
    note.applyCss();
    note.autosize();
    double x = Math.max(4, Math.min(at.getX(), dropLayer.getWidth() - note.getWidth() - 4));
    double y = Math.max(4, Math.min(at.getY(), dropLayer.getHeight() - note.getHeight() - 4));
    note.relocate(x, y);
    return note;
  }

  /**
   * The screen x of the first text column and the width of one column (the
   * editor's font is monospaced), measured on a line shown: from one
   * character to the next, as a character's own bounds are a little wider.
   */
  private double[] textColumn() {
    for (int i = 0; i < area.getVisibleParagraphs().size(); i++) {
      int line = area.visibleParToAllParIndex(i);
      String text = area.getParagraph(line).getText();
      if (text.length() < 2 || text.charAt(0) == '\t' || text.charAt(1) == '\t') continue;
      int start = area.getAbsolutePosition(line, 0);
      var first = area.getCharacterBoundsOnScreen(start, start + 1);
      var second = area.getCharacterBoundsOnScreen(start + 1, start + 2);
      if (first.isPresent() && second.isPresent()) {
        double x = first.get().getMinX();
        return new double[] {x, second.get().getMinX() - x};
      }
    }
    javafx.geometry.Bounds view = area.localToScreen(area.getLayoutBounds());
    return new double[] {view.getMinX(), FONT_SIZE.get() * 0.6};
  }

  /** Opens {@code rows} lines below (or above) {@code line} for the preview; -1 closes them. */
  private void setDropGap(int line, boolean above, int rows) {
    int next = rows > 0 ? line : -1;
    if (next == dropGapLine && above == dropGapAbove && rows == dropGapRows) return;
    int old = dropGapLine;
    dropGapLine = next;
    dropGapAbove = above;
    dropGapRows = next < 0 ? 0 : rows;
    // the line's text gets padding (its paragraph style); a new gutter keeps beside the text
    if (old >= 0) paragraphStyle(old + 1);
    if (next >= 0 && next != old) paragraphStyle(next + 1);
  }

  /**
   * The paddings that open lines for a preview, in pixels of the current line
   * height (a stylesheet of its own: CSS has no other way to size them).
   */
  private void ensureGapStyles(double row) {
    if (row == gapStyleRow) return;
    StringBuilder css = new StringBuilder();
    for (int rows = 1; rows <= MAX_GAP_ROWS; rows++) {
      double px = rows * row;
      css.append(".paragraph-text.drop-gap-above-").append(rows)
          .append(" { -fx-padding: ").append(px).append(" 0 0 0; }\n")
          .append(".paragraph-text.drop-gap-below-").append(rows)
          .append(" { -fx-padding: 0 0 ").append(px).append(" 0; }\n");
    }
    if (gapStyles != null) area.getStylesheets().remove(gapStyles);
    gapStyles = "data:text/css;base64," + java.util.Base64.getEncoder().encodeToString(
        css.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    area.getStylesheets().add(gapStyles);
    gapStyleRow = row;
  }

  // --- hover -------------------------------------------------------------------

  private void installHover() {
    hoverPopup.setAutoHide(true);
    area.setMouseOverTextDelay(Duration.ofMillis(350));
    area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, e -> {
      int index = e.getCharacterIndex();
      for (int[] range : diagnosticRanges) {
        if (index >= range[0] && index <= range[1]) {
          Diagnostic d = diagnostics.get(range[2]);
          VBox box = new VBox(4);
          box.getStyleClass().addAll("hover-card", d.error() ? "error" : "warning");
          Label message = new Label(d.message());
          message.getStyleClass().add("hover-message");
          message.setWrapText(true);
          box.getChildren().add(message);
          if (d.explanation() != null && !d.explanation().isBlank()) {
            Label explanation = new Label(d.explanation());
            explanation.setWrapText(true);
            explanation.getStyleClass().add("hover-explanation");
            box.getChildren().add(explanation);
          }
          box.setMaxWidth(460);
          box.getStylesheets().setAll(getScene().getStylesheets());
          if (Theme.isDark()) {
            box.getStyleClass().add("dark");
          }
          hoverPopup.getContent().setAll(box);
          hoverPopup.show(area, e.getScreenPosition().getX(), e.getScreenPosition().getY() + 14);
          return;
        }
      }
    });
    area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, e -> hoverPopup.hide());
  }

  // --- completion ------------------------------------------------------------

  private void installCompletionPopup() {
    completionPopup.setAutoHide(true);
    // a click elsewhere closes the suggestions and still does its job
    completionPopup.setConsumeAutoHidingEvents(false);
    // an open popup gets the window's keys first (its list would swallow Enter): the
    // suggestion keys are handled here; Enter/Tab on a complete word do their normal job
    completionPopup.getScene().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
      if (handleCompletionKey(e)) {
        e.consume();
      } else if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.TAB) {
        completionPopup.hide();
        if (e.getCode() == KeyCode.ENTER) {
          newlineWithIndent();
        } else {
          area.replaceSelection(INDENT);
        }
        e.consume();
      }
    });
    completionPopup.setOnHidden(e -> {
      semanticItems = null;
      semanticStart = -1;
      // an answer still on its way doesn't open closed suggestions again
      semanticRequest++;
    });
    completionList.getStyleClass().add("completion-list");
    completionList.setPrefSize(380, 230);
    completionList.setFocusTraversable(false);
    completionList.setCellFactory(v -> new ListCell<>() {
      @Override
      protected void updateItem(Completion item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText(null);
          setGraphic(null);
          return;
        }
        setText(item.label());
        setGraphic(completionIcon(item));
      }
    });
    completionList.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
      completionDetail.setText(item == null ? "" : item.detail());
      boolean docs = item != null && item.docsUrl() != null;
      completionDocs.setVisible(docs);
      completionDocs.setManaged(docs);
      completionDocs.setOnAction(e -> {
        if (item != null && item.docsUrl() != null) browse.accept(item.docsUrl());
      });
    });
    completionDocs.setText(I18n.t("completion.docs"));
    // a focusable link would get the keys the popup sees first (the arrows move focus)
    completionDocs.setFocusTraversable(false);
    completionDocs.setVisible(false);
    completionDocs.setManaged(false);
    completionList.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2) {
        acceptCompletion();
      }
    });
    completionDetail.getStyleClass().add("completion-detail");
    completionDetail.setWrapText(true);
    completionDetail.setMaxWidth(380);
    completionDetail.setMinHeight(Region.USE_PREF_SIZE);
    javafx.scene.control.ScrollPane detailScroll =
        new javafx.scene.control.ScrollPane(new VBox(2, completionDetail, completionDocs));
    detailScroll.setFitToWidth(true);
    detailScroll.setMaxHeight(200);
    detailScroll.setPrefViewportHeight(120);
    detailScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
    detailScroll.getStyleClass().add("completion-detail-scroll");
    VBox box = new VBox(completionList, detailScroll);
    box.getStyleClass().add("completion-popup");
    completionPopup.getContent().add(box);
    area.focusedProperty().addListener((o, was, focused) -> {
      if (!focused) {
        completionPopup.hide();
      }
    });
    // auto-hide leaves clicks on its owner, the code, alone: they close it here
    area.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> completionPopup.hide());
  }

  private Node completionIcon(Completion item) {
    if (item.kind() == Completion.Kind.IMAGE) {
      ImageView view = new ImageView(CostumeView.costume(item.text()));
      view.setFitWidth(22);
      view.setFitHeight(22);
      view.setPreserveRatio(true);
      return view;
    }
    String literal = switch (item.kind()) {
      case METHOD -> "fth-box";
      case FIELD -> "fth-tag";
      case VARIABLE -> "fth-at-sign";
      case CLASS -> "fth-layers";
      case KEYWORD -> "fth-hash";
      case WORD -> "fth-type";
      case SOUND -> "fth-music";
      case FILE -> "fth-file";
      case IMAGE -> "fth-image";
    };
    var icon = Icons.of(literal);
    icon.getStyleClass().add("completion-kind-" + item.kind().name().toLowerCase(Locale.ROOT));
    return icon;
  }

  private boolean handleCompletionKey(KeyEvent e) {
    switch (e.getCode()) {
      case UP -> {
        int i = completionList.getSelectionModel().getSelectedIndex();
        completionList.getSelectionModel().select(Math.max(0, i - 1));
        completionList.scrollTo(completionList.getSelectionModel().getSelectedIndex());
        return true;
      }
      case DOWN -> {
        int i = completionList.getSelectionModel().getSelectedIndex();
        completionList.getSelectionModel().select(
            Math.min(completionList.getItems().size() - 1, i + 1));
        completionList.scrollTo(completionList.getSelectionModel().getSelectedIndex());
        return true;
      }
      case ENTER, TAB -> {
        // the word is already complete: Enter makes the new line, Tab indents
        Completion selected = completionList.getSelectionModel().getSelectedItem();
        if (selected != null && selected.text().equals(wordBeforeCaret())
            && selected.kind() != Completion.Kind.METHOD) {
          completionPopup.hide();
          return false;
        }
        acceptCompletion();
        return true;
      }
      case ESCAPE -> {
        completionPopup.hide();
        return true;
      }
      case LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN -> {
        // the caret leaves the word: the suggestions go, the key moves the caret
        completionPopup.hide();
        return false;
      }
      default -> {
        return false;
      }
    }
  }

  /** The lines (0-based) marked as errors (tests). */
  java.util.Set<Integer> errorLineNumbers() {
    return java.util.Set.copyOf(errorLines);
  }

  /** Whether the suggestions are open (tests). */
  boolean completionShowing() {
    return completionPopup.isShowing();
  }

  /** The identifier part right before the caret. */
  private String wordBeforeCaret() {
    String before = textBeforeCaretOnLine();
    int i = before.length();
    while (i > 0 && Character.isJavaIdentifierPart(before.charAt(i - 1))) i--;
    return before.substring(i);
  }

  /** At least two letters of a word, outside comments and strings (asset strings aside). */
  private boolean typingWord() {
    String before = textBeforeCaretOnLine();
    if (wordBeforeCaret().length() < 2 || Character.isDigit(wordBeforeCaret().charAt(0))) {
      return false;
    }
    boolean inString = false;
    for (int i = 0; i < before.length(); i++) {
      char c = before.charAt(i);
      if (c == '\\' && inString) {
        i++;
      } else if (c == '"') {
        inString = !inString;
      } else if (!inString && c == '/' && i + 1 < before.length()
          && (before.charAt(i + 1) == '/' || before.charAt(i + 1) == '*')) {
        return false;
      }
    }
    return !inString && !before.stripLeading().startsWith("*");
  }

  private boolean insideAssetString() {
    String before = textBeforeCaretOnLine();
    return IMAGE_STRING.matcher(before).find() || SOUND_STRING.matcher(before).find()
        || MAP_STRING.matcher(before).find() || LAYER_STRING.matcher(before).find();
  }

  private String textBeforeCaretOnLine() {
    return area.getParagraph(area.getCurrentParagraph()).getText()
        .substring(0, area.getCaretColumn());
  }

  /** Candidates for the caret position: asset names inside asset strings, else identifiers. */
  List<Completion> completions() {
    String before = textBeforeCaretOnLine();
    Matcher image = IMAGE_STRING.matcher(before);
    if (image.find()) {
      completionStart = area.getCaretPosition() - image.group(2).length();
      return assetCompletions(image.group(2), true);
    }
    Matcher mapPath = MAP_STRING.matcher(before);
    if (mapPath.find()) {
      completionStart = area.getCaretPosition() - mapPath.group(1).length();
      return mapCompletions(mapPath.group(1));
    }
    Matcher layer = LAYER_STRING.matcher(before);
    if (layer.find()) {
      completionStart = area.getCaretPosition() - layer.group(2).length();
      return layerCompletions(layer.group(2), layer.group(1).equals("getObjectsFromLayer")
          || layer.group(1).equals("spawn"));
    }
    Matcher sound = SOUND_STRING.matcher(before);
    if (sound.find()) {
      completionStart = area.getCaretPosition() - sound.group(2).length();
      return assetCompletions(sound.group(2), false);
    }
    String prefix = "";
    Matcher id = IDENTIFIER.matcher(before);
    if (id.find()) {
      prefix = id.group();
    }
    completionStart = area.getCaretPosition() - prefix.length();
    boolean afterDot = before.length() > prefix.length()
        && before.charAt(before.length() - prefix.length() - 1) == '.';
    if (semanticItems != null && semanticStart == completionStart) {
      return filterSemantic(prefix);
    }
    return identifierCompletions(prefix, afterDot);
  }

  /** Cached semantic items narrowed to the typed prefix (keywords added outside member access). */
  private List<Completion> filterSemantic(String prefix) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    List<Completion> result = new ArrayList<>();
    for (Completion item : semanticItems) {
      if (item.text().toLowerCase(Locale.ROOT).startsWith(lower)) {
        result.add(item);
      }
    }
    if (!semanticMemberAccess && !prefix.isEmpty()) {
      for (String k : JAVA_KEYWORDS) {
        if (k.startsWith(lower) && !k.equals(prefix)) {
          result.add(new Completion(k, "Java", Completion.Kind.KEYWORD));
        }
      }
    }
    result.sort((a, b) -> Boolean.compare(!a.text().startsWith(prefix),
        !b.text().startsWith(prefix)));
    return result.size() > 200 ? result.subList(0, 200) : result;
  }

  /** The semantic completions at the caret, computed synchronously (tests). */
  List<Completion> semanticCompletionsNow() throws java.io.IOException {
    if (semanticCompleter == null) {
      return List.of();
    }
    List<Completion> items = new ArrayList<>();
    for (var item : semanticCompleter.complete(file, area.getText(), area.getCaretPosition())
        .items()) {
      items.add(semanticCompletion(item));
    }
    return items;
  }

  void setSemanticCompleter(SemanticCompleter completer) {
    this.semanticCompleter = completer;
  }

  void setBrowse(java.util.function.Consumer<String> browse) {
    this.browse = browse;
  }

  /**
   * Asks javac for the names at the caret off the FX thread; when the answer
   * arrives and the caret is still on the same identifier, the popup switches
   * to it and keeps it while the user types.
   */
  private void requestSemantic(boolean explicit) {
    if (semanticCompleter == null || insideAssetString()) {
      return;
    }
    int caret = area.getCaretPosition();
    int start = completionStart;
    if (semanticItems != null && semanticStart == start) {
      return;
    }
    String text = area.getText();
    long request = ++semanticRequest;
    COMPLETION_WORKER.submit(() -> {
      try {
        var result = semanticCompleter.complete(file, text, caret);
        List<Completion> items = new ArrayList<>();
        for (var item : result.items()) {
          items.add(semanticCompletion(item));
        }
        Platform.runLater(() -> {
          if (request != semanticRequest || completionStart != result.start()) {
            return;
          }
          semanticItems = items;
          semanticStart = result.start();
          semanticMemberAccess = result.memberAccess();
          if (completionPopup.isShowing() || explicit || result.memberAccess()) {
            showCompletion(false);
          }
        });
      } catch (java.io.IOException | RuntimeException e) {
        // keep the name-based list
      }
    });
  }

  private Completion semanticCompletion(org.openpatch.scratch4j.core.compile.Completions.Item item) {
    String ownerSimple = item.owner() == null ? null
        : item.owner().substring(item.owner().lastIndexOf('.') + 1);
    ApiMethod doc = null;
    if (item.kind() == org.openpatch.scratch4j.core.compile.Completions.Kind.METHOD
        && item.owner() != null && item.owner().startsWith("org.openpatch.scratch.")) {
      doc = apiIndex.byName(item.name()).stream()
          .filter(m -> m.className().equals(ownerSimple)
              && m.methodName().equals(item.name())
              && m.params().stream().map(p -> parameterType(p.replaceFirst("\\s+\\w+$", "")))
                  .toList().equals(item.parameterTypes().stream()
                      .map(CodeEditor::parameterType).toList()))
          .findFirst().orElse(null);
    }
    String signature = doc == null ? item.signature() : methodLabel(doc);
    StringBuilder detail = new StringBuilder();
    if (ownerSimple != null) {
      detail.append(ownerSimple).append(" \u00b7 ");
    }
    detail.append(item.type()).append(' ').append(signature);
    if (doc != null) {
      detail.append(javadoc(doc));
    }
    Completion.Kind kind = switch (item.kind()) {
      case METHOD -> Completion.Kind.METHOD;
      case FIELD -> Completion.Kind.FIELD;
      case VARIABLE -> Completion.Kind.VARIABLE;
      case CLASS -> Completion.Kind.CLASS;
    };
    return new Completion(item.name(), detail.toString(), kind,
        item.kind() == org.openpatch.scratch4j.core.compile.Completions.Kind.METHOD
            ? item.parameterTypes().isEmpty() : null,
        doc == null ? null : doc.docsUrl(), signature);
  }

  /** Compare catalog and javac types without packages, formatting, or varargs syntax. */
  private static String parameterType(String type) {
    return type.replace("...", "[]").replaceAll("\\b[a-z][\\w]*\\.", "")
        .replaceAll("\\s+", "");
  }

  private static String methodLabel(ApiMethod method) {
    return method.methodName() + "(" + String.join(", ", method.params()) + ")";
  }

  /** The Javadoc part of a detail: Scratch block, description, parameters, return value. */
  static String javadoc(ApiMethod m) {
    StringBuilder sb = new StringBuilder();
    if (m.scratchblock() != null) {
      sb.append("\n\u25B8 ").append(m.scratchblock());
    }
    String description = m.description() != null && !m.description().isBlank()
        ? m.description() : m.summary();
    if (description != null && !description.isBlank()) {
      sb.append("\n\n").append(description);
    }
    if (!m.paramDocs().isEmpty()) {
      sb.append("\n");
      for (String param : m.paramDocs()) {
        sb.append("\n\u2022 ").append(param);
      }
    }
    if (m.returns() != null && !m.returns().isBlank()) {
      sb.append("\n\u21B3 ").append(m.returns());
    }
    return sb.toString();
  }

  /** Plain names for tests and simple callers. */
  List<String> completionCandidates() {
    return completions().stream().map(Completion::text).toList();
  }

  private List<Completion> identifierCompletions(String prefix, boolean afterDot) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    Map<String, Completion> found = new LinkedHashMap<>();
    for (ApiMethod m : apiIndex.methods()) {
      if ("constructor".equals(m.methodName())
          || !m.methodName().toLowerCase(Locale.ROOT).startsWith(lower)) {
        continue;
      }
      found.putIfAbsent(m.methodName(), new Completion(m.methodName(), detailOf(m),
          Completion.Kind.METHOD, m.params().isEmpty(), m.docsUrl(), methodLabel(m)));
    }
    if (!afterDot) {
      for (String k : JAVA_KEYWORDS) {
        if (k.startsWith(lower) && !k.equals(prefix)) {
          found.putIfAbsent(k, new Completion(k, "Java", Completion.Kind.KEYWORD));
        }
      }
    }
    Matcher words = WORD.matcher(area.getText());
    Set<String> seen = new HashSet<>();
    while (words.find()) {
      String w = words.group();
      if (!w.equals(prefix) && w.toLowerCase(Locale.ROOT).startsWith(lower) && seen.add(w)) {
        found.putIfAbsent(w, new Completion(w, "", Completion.Kind.WORD));
      }
    }
    if (prefix.isEmpty() && !afterDot) {
      return List.of();
    }
    List<Completion> sorted = new ArrayList<>(found.values());
    sorted.sort((a, b) -> {
      boolean aCase = a.text().startsWith(prefix);
      boolean bCase = b.text().startsWith(prefix);
      if (aCase != bCase) {
        return aCase ? -1 : 1;
      }
      return a.kind() != b.kind() ? a.kind().compareTo(b.kind())
          : a.text().compareToIgnoreCase(b.text());
    });
    return sorted.size() > 200 ? sorted.subList(0, 200) : sorted;
  }

  private static String detailOf(ApiMethod m) {
    return m.className() + " \u00b7 " + m.signature() + javadoc(m);
  }

  /** The project's .tmx files, as paths from the project folder. */
  private List<Completion> mapCompletions(String prefix) {
    Path root = projectRoot.get();
    if (root == null) return List.of();
    String lower = prefix.toLowerCase(Locale.ROOT);
    List<Completion> result = new ArrayList<>();
    for (var map : new org.openpatch.scratch4j.core.tiled.MapLinter(
        org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language.EN).allMaps(root)) {
      String path = root.relativize(map.map()).toString().replace('\\', '/');
      if (path.toLowerCase(Locale.ROOT).contains(lower)) {
        result.add(new Completion(path, I18n.t("completion.map"), Completion.Kind.FILE));
      }
    }
    return result;
  }

  /** Layer names of the project's maps: object layers or tile layers. */
  private List<Completion> layerCompletions(String prefix, boolean objectLayers) {
    Path root = projectRoot.get();
    if (root == null) return List.of();
    String lower = prefix.toLowerCase(Locale.ROOT);
    java.util.Map<String, List<String>> where = new java.util.LinkedHashMap<>();
    for (var map : new org.openpatch.scratch4j.core.tiled.MapLinter(
        org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language.EN).allMaps(root)) {
      for (String name : objectLayers ? map.objects() : map.tiles()) {
        if (name.toLowerCase(Locale.ROOT).contains(lower)) {
          where.computeIfAbsent(name, n -> new ArrayList<>())
              .add(map.map().getFileName().toString());
        }
      }
    }
    List<Completion> result = new ArrayList<>();
    String key = objectLayers ? "completion.maplayer.objects" : "completion.maplayer.tiles";
    where.forEach((name, maps) -> result.add(new Completion(name,
        I18n.t(key, String.join(", ", maps)), Completion.Kind.WORD)));
    return result;
  }

  private List<Completion> assetCompletions(String prefix, boolean images) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    List<Completion> result = new ArrayList<>();
    Path root = projectRoot.get();
    if (root != null) {
      Path dir = root.resolve(images ? "assets/images" : "assets/sounds");
      if (Files.isDirectory(dir)) {
        try (Stream<Path> files = Files.walk(dir)) {
          files.filter(Files::isRegularFile)
              .map(p -> root.relativize(p).toString().replace('\\', '/'))
              .filter(p -> p.toLowerCase(Locale.ROOT).contains(lower))
              .sorted()
              .forEach(p -> result.add(new Completion(p, I18n.t("completion.projectfile"),
                  Completion.Kind.FILE)));
        } catch (java.io.IOException ignored) {
          // unreadable assets folder: built-ins only
        }
      }
    }
    BuiltinAssetIndex assets = BuiltinAssetIndex.get();
    if (images) {
      assets.images().stream()
          .filter(i -> i.referenceName().toLowerCase(Locale.ROOT).contains(lower))
          .sorted((a, b) -> Boolean.compare(
              !a.referenceName().toLowerCase(Locale.ROOT).startsWith(lower),
              !b.referenceName().toLowerCase(Locale.ROOT).startsWith(lower)))
          .limit(150)
          .forEach(i -> result.add(new Completion(i.referenceName(),
              I18n.t("completion.builtin.image", i.sheet(), i.width(), i.height()),
              Completion.Kind.IMAGE)));
    } else {
      assets.sounds().stream()
          .filter(s -> s.toLowerCase(Locale.ROOT).contains(lower))
          .limit(150)
          .forEach(s -> result.add(new Completion(s, I18n.t("completion.builtin.sound"),
              Completion.Kind.SOUND)));
    }
    return result;
  }

  private void showCompletion(boolean explicit) {
    List<Completion> candidates = completions();
    requestSemantic(explicit);
    if (candidates.isEmpty() || !explicit && candidates.size() == 1
        && candidates.get(0).text().equals(wordBeforeCaret())) {
      completionPopup.hide();
      return;
    }
    if (explicit && candidates.size() == 1 && completionStart >= 0
        && !insideAssetString()) {
      completionList.getItems().setAll(candidates);
      completionList.getSelectionModel().selectFirst();
      acceptCompletion();
      return;
    }
    completionList.getItems().setAll(candidates);
    completionList.getSelectionModel().selectFirst();
    completionList.scrollTo(0);
    completionList.setPrefHeight(Math.min(9, candidates.size()) * 30 + 6);
    var box = (VBox) completionPopup.getContent().get(0);
    box.getStylesheets().setAll(getScene().getStylesheets());
    box.getStyleClass().remove("dark");
    if (Theme.isDark()) {
      box.getStyleClass().add("dark");
    }
    area.getCaretBounds().ifPresent(bounds -> {
      if (!completionPopup.isShowing()) {
        completionPopup.show(area, bounds.getMinX(), bounds.getMaxY() + 2);
      }
    });
  }

  private void acceptCompletion() {
    Completion selected = completionList.getSelectionModel().getSelectedItem();
    completionPopup.hide();
    if (selected == null || completionStart < 0) {
      return;
    }
    int caret = area.getCaretPosition();
    String text = selected.text();
    boolean method = selected.kind() == Completion.Kind.METHOD;
    boolean followedByParen = caret < area.getLength()
        && area.getText(caret, caret + 1).equals("(");
    if (method && !followedByParen) {
      text += "()";
    }
    area.replaceText(completionStart, caret, text);
    if (method && !followedByParen) {
      boolean noArgs = selected.noArgs() != null ? selected.noArgs()
          : apiIndex.byName(selected.text()).stream().anyMatch(m -> m.params().isEmpty());
      area.moveTo(completionStart + text.length() - (noArgs ? 0 : 1));
    }
    area.requestFocus();
  }

  // --- find / replace ----------------------------------------------------------

  /** The find/replace bar above the editor (Ctrl+F / Ctrl+H, Esc closes). */
  static final class FindBar extends VBox {

    private final CodeArea area;
    private final javafx.scene.control.TextField find = new javafx.scene.control.TextField();
    private final javafx.scene.control.TextField replace =
        new javafx.scene.control.TextField();
    private final javafx.scene.control.CheckBox matchCase =
        new javafx.scene.control.CheckBox("Aa");
    private final Label count = new Label();
    private final HBox replaceRow;

    FindBar(CodeArea area) {
      this.area = area;
      getStyleClass().add("find-bar");
      find.setPromptText(I18n.t("find.prompt"));
      replace.setPromptText(I18n.t("find.replace.prompt"));
      HBox.setHgrow(find, Priority.ALWAYS);
      HBox.setHgrow(replace, Priority.ALWAYS);
      matchCase.setTooltip(new javafx.scene.control.Tooltip(I18n.t("find.matchcase")));
      count.getStyleClass().add("text-muted");
      HBox findRow = new HBox(6, Icons.of("fth-search"), find, count, matchCase,
          Icons.button("fth-chevron-up", I18n.t("find.previous"), () -> next(false)),
          Icons.button("fth-chevron-down", I18n.t("find.next"), () -> next(true)),
          Icons.button("fth-x", I18n.t("find.close"), this::close));
      findRow.setAlignment(Pos.CENTER_LEFT);
      javafx.scene.control.Button one = new javafx.scene.control.Button(I18n.t("find.replace"));
      one.setOnAction(e -> replaceOne());
      javafx.scene.control.Button all =
          new javafx.scene.control.Button(I18n.t("find.replaceall"));
      all.setOnAction(e -> replaceAll());
      replaceRow = new HBox(6, Icons.of("fth-repeat"), replace, one, all);
      replaceRow.setAlignment(Pos.CENTER_LEFT);
      getChildren().addAll(findRow, replaceRow);
      find.textProperty().addListener((o, old, v) -> {
        updateCount();
        if (!v.isEmpty()) {
          area.moveTo(area.getSelection().getStart());
          next(true);
        }
      });
      matchCase.selectedProperty().addListener((o, old, v) -> updateCount());
      find.setOnAction(e -> next(true));
      replace.setOnAction(e -> replaceOne());
      for (javafx.scene.control.TextField field : List.of(find, replace)) {
        field.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
          if (e.getCode() == KeyCode.ESCAPE) {
            close();
            e.consume();
          } else if (e.getCode() == KeyCode.ENTER && e.isShiftDown()) {
            next(false);
            e.consume();
          }
        });
      }
      setVisible(false);
      setManaged(false);
    }

    void open(boolean withReplace) {
      setVisible(true);
      setManaged(true);
      replaceRow.setVisible(withReplace);
      replaceRow.setManaged(withReplace);
      String selected = area.getSelectedText();
      if (!selected.isEmpty() && !selected.contains("\n")) {
        find.setText(selected);
      }
      find.requestFocus();
      find.selectAll();
      updateCount();
    }

    void close() {
      setVisible(false);
      setManaged(false);
      area.requestFocus();
    }

    private String haystack() {
      return matchCase.isSelected() ? area.getText()
          : area.getText().toLowerCase(Locale.ROOT);
    }

    private String needle() {
      return matchCase.isSelected() ? find.getText()
          : find.getText().toLowerCase(Locale.ROOT);
    }

    private void updateCount() {
      String needle = needle();
      if (needle.isEmpty()) {
        count.setText("");
        return;
      }
      String text = haystack();
      int n = 0;
      for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
        n++;
      }
      count.setText(I18n.t("find.count", n));
      find.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("no-match"), n == 0);
    }

    void next(boolean forward) {
      String needle = needle();
      if (needle.isEmpty()) {
        return;
      }
      String text = haystack();
      int from = forward ? area.getSelection().getEnd() : area.getSelection().getStart() - 1;
      int at = forward ? text.indexOf(needle, from) : text.lastIndexOf(needle, Math.max(0, from));
      if (at < 0) {
        at = forward ? text.indexOf(needle) : text.lastIndexOf(needle);
      }
      if (at >= 0) {
        area.selectRange(at, at + needle.length());
        area.requestFollowCaret();
      }
    }

    private void replaceOne() {
      if (!area.getSelectedText().isEmpty() && (matchCase.isSelected()
          ? area.getSelectedText().equals(find.getText())
          : area.getSelectedText().equalsIgnoreCase(find.getText()))) {
        area.replaceSelection(replace.getText());
      }
      next(true);
      updateCount();
    }

    private void replaceAll() {
      String needle = find.getText();
      if (needle.isEmpty()) {
        return;
      }
      Pattern p = Pattern.compile(Pattern.quote(needle),
          matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      area.replaceText(p.matcher(area.getText())
          .replaceAll(Matcher.quoteReplacement(replace.getText())));
      updateCount();
    }
  }
}
