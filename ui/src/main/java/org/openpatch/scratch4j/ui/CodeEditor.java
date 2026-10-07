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
final class CodeEditor extends BorderPane {

  /** One diagnostic shown in the editor (1-based line/column; column 0 = whole line). */
  record Diagnostic(long line, long column, String message, String explanation,
      boolean error) {}

  /**
   * One completion entry. {@code noArgs} is known for semantic methods (the
   * caret then lands after "()"), {@code docsUrl} links the reference page.
   */
  record Completion(String text, String detail, Kind kind, Boolean noArgs, String docsUrl) {
    enum Kind { METHOD, FIELD, VARIABLE, CLASS, KEYWORD, WORD, IMAGE, SOUND, FILE }

    Completion(String text, String detail, Kind kind) {
      this(text, detail, kind, null, null);
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
      "\\b(getObjectsFromLayer|stampLayerToBackground|stampLayerToForeground)"
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
  private Button visualModeButton;
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

    area.multiPlainChanges().successionEnds(Duration.ofMillis(40)).subscribe(ignore -> {
      rehighlight();
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
    setTop(findBar);
    setCenter(new VirtualizedScrollPane<>(area));
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

  /**
   * A "Visual" button floating in the editor's top-right corner (no extra
   * row) that switches to the class's visual editor.
   */
  void setVisualModeAction(String label, String tooltip, Runnable action) {
    if (visualModeButton != null) return;
    visualModeButton = Icons.labeled("fth-monitor", label, action);
    visualModeButton.setTooltip(new Tooltip(tooltip));
    visualModeButton.getStyleClass().add("floating-mode-button");
    StackPane layered = new StackPane(getCenter(), visualModeButton);
    StackPane.setAlignment(visualModeButton, Pos.TOP_RIGHT);
    // clear of the vertical scroll bar
    StackPane.setMargin(visualModeButton, new javafx.geometry.Insets(6, 22, 0, 0));
    setCenter(layered);
  }

  /** The code (with its floating button) while a visual panel is beside it. */
  private javafx.scene.Node codePane;
  private javafx.scene.Node sidePanel;
  private String sidePanelKey;
  private String visualLabel;
  private Tooltip visualTooltip;

  /**
   * The class's visual editor (stage designer, sprite editor) to the right
   * of the code, in this tab; replaces another one that was there.
   */
  void showSidePanel(String key, javafx.scene.Node panel) {
    if (codePane == null) {
      codePane = getCenter();
    }
    javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(codePane, panel);
    split.getStyleClass().add("code-visual-split");
    split.setDividerPositions(0.42);
    setCenter(split);
    sidePanel = panel;
    sidePanelKey = key;
    if (visualModeButton != null) {
      visualLabel = visualModeButton.getText();
      // an icon only: the code beside the panel is narrow
      visualModeButton.setText(null);
      visualModeButton.setGraphic(Icons.of("fth-x"));
      visualTooltip = visualModeButton.getTooltip();
      visualModeButton.setTooltip(new Tooltip(I18n.t("mode.visual.close")));
    }
  }

  /** Back to the code alone. */
  void hideSidePanel() {
    if (sidePanel == null) return;
    setCenter(codePane);
    sidePanel = null;
    sidePanelKey = null;
    if (visualModeButton != null && visualLabel != null) {
      visualModeButton.setText(visualLabel);
      visualModeButton.setGraphic(Icons.of("fth-monitor"));
      visualModeButton.setTooltip(visualTooltip);
    }
  }

  javafx.scene.Node sidePanel() {
    return sidePanel;
  }

  String sidePanelKey() {
    return sidePanelKey;
  }

  /** The floating Visual button, or null for classes without a visual editor. */
  Button visualModeButton() {
    return visualModeButton;
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
   * Inserts a palette snippet. Statements (ending in a newline) go on their
   * own line below the caret line, at its indent; expressions go at the caret.
   */
  void insertBlock(String snippet) {
    if (!snippet.endsWith("\n")) {
      insertAtCaret(snippet);
      return;
    }
    String body = snippet.substring(0, snippet.length() - 1);
    boolean multiLine = body.contains("\n");
    int paragraph = area.getCurrentParagraph();
    String line = area.getParagraph(paragraph).getText();
    boolean blank = line.isBlank();
    // a blank line has no indent of its own: take it from the code around
    String indent = blank ? contextIndent(paragraph)
        : indentOf(line) + (line.strip().endsWith("{") ? INDENT : "");
    boolean prevIsBlank = blank
        ? paragraph == 0 || area.getParagraph(paragraph - 1).getText().isBlank()
        : false;
    boolean nextIsBlank = paragraph + 1 >= area.getParagraphs().size()
        || area.getParagraph(paragraph + 1).getText().isBlank();
    int lineStart = area.getAbsolutePosition(paragraph, 0);
    // multi-line snippets (method overrides) keep a blank line on both sides
    String before = blank ? (multiLine && !prevIsBlank ? "\n" : "")
        : (multiLine ? "\n\n" : "\n");
    String indented = body.replace("\n", "\n" + indent);
    String after = multiLine && !nextIsBlank ? "\n" : "";
    int insertAt;
    if (blank) {
      area.replaceText(lineStart, lineStart + line.length(), before + indent + indented + after);
      insertAt = lineStart + before.length() + indent.length();
    } else {
      int lineEnd = lineStart + line.length();
      area.insertText(lineEnd, before + indent + indented + after);
      insertAt = lineEnd + before.length() + indent.length();
    }
    // an empty method body: put the caret inside it
    int emptyBody = indented.indexOf("{\n" + indent + INDENT + "\n");
    if (emptyBody >= 0) {
      area.moveTo(insertAt + emptyBody + 2 + indent.length() + INDENT.length());
    } else {
      area.moveTo(insertAt + indented.length());
    }
    area.requestFocus();
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

  private static String indentOf(String line) {
    int i = 0;
    while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
      i++;
    }
    return line.substring(0, i);
  }

  /** The indent a new line at blank {@code paragraph} should get. */
  private String contextIndent(int paragraph) {
    String previous = null;
    for (int p = paragraph - 1; p >= 0 && previous == null; p--) {
      String text = area.getParagraph(p).getText();
      if (!text.isBlank()) {
        previous = text;
      }
    }
    String next = null;
    for (int p = paragraph + 1; p < area.getParagraphs().size() && next == null; p++) {
      String text = area.getParagraph(p).getText();
      if (!text.isBlank()) {
        next = text;
      }
    }
    if (previous != null && previous.strip().endsWith("{")) {
      return indentOf(previous) + INDENT;
    }
    if (next != null && next.strip().startsWith("}")) {
      return indentOf(next) + INDENT;
    }
    if (next != null) {
      return indentOf(next);
    }
    return previous == null ? "" : indentOf(previous);
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

  private void paragraphStyle(int line) {
    if (line <= 0 || line - 1 >= area.getParagraphs().size()) return;
    List<String> styles = new ArrayList<>();
    if (line == debugLine) styles.add("debug-line");
    if (linkedLines.contains(line)) styles.add("linked-line");
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
      HBox box = new HBox(marker, bulb, number, previewSlot);
      box.setAlignment(Pos.CENTER_LEFT);
      box.getStyleClass().add("gutter");
      box.setCursor(javafx.scene.Cursor.HAND);
      box.setOnMouseClicked(e -> {
        if (!breakpoints.remove(line + 1)) {
          breakpoints.add(line + 1);
        }
        e.consume();
      });
      return box;
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

  /** Blocks dragged from the palette drop in at the line under the pointer. */
  private void installBlockDrop() {
    area.setOnDragOver(e -> {
      if (e.getGestureSource() != area && e.getDragboard().hasString()) {
        e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
        int index = area.hit(e.getX(), e.getY()).getInsertionIndex();
        area.moveTo(index);
      }
      e.consume();
    });
    area.setOnDragDropped(e -> {
      boolean done = false;
      if (e.getDragboard().hasString()) {
        area.moveTo(area.hit(e.getX(), e.getY()).getInsertionIndex());
        insertBlock(e.getDragboard().getString());
        done = true;
      }
      e.setDropCompleted(done);
      e.consume();
    });
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
        setText(item.text());
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
      return layerCompletions(layer.group(2), layer.group(1).equals("getObjectsFromLayer"));
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
        && ownerSimple != null) {
      doc = apiIndex.byName(item.name()).stream()
          .filter(m -> m.className().equals(ownerSimple)
              && m.params().size() == item.parameterTypes().size())
          .findFirst().orElse(null);
    }
    StringBuilder detail = new StringBuilder();
    if (ownerSimple != null) {
      detail.append(ownerSimple).append(" \u00b7 ");
    }
    detail.append(item.type()).append(' ').append(item.signature());
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
        doc == null ? null : doc.docsUrl());
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
          Completion.Kind.METHOD));
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
