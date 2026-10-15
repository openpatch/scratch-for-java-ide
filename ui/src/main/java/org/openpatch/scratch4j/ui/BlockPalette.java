package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.api.ApiMethod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The Scratch-style block pane at the left of a class's code: a rail of
 * coloured categories and one list of all blocks under category headings,
 * as in Scratch. A category on the rail scrolls to its blocks and the rail
 * follows the scrolling. Blocks are drawn as Scratch blocks with their Java
 * call underneath; double-click or drag into the editor inserts the Java
 * code, event blocks ({@code when ...}) insert a method override instead of
 * a call. The pane folds to its rail to give the code room.
 */
final class BlockPalette extends BorderPane {

  /** Scratch's own category order; other categories follow alphabetically. */
  private static final List<String> ORDER = List.of("Motion", "Looks", "Sound", "Events",
      "Control", "Sensing", "Operators", "Pen", "Clock");

  /** A heading (no method) or a block in the list. */
  record Entry(String category, ApiMethod method) {
    boolean isHeading() {
      return method == null;
    }
  }

  private final ApiIndex index;
  private final ListView<Entry> list = new ListView<>();
  private final TextField search = new TextField();
  private final ToggleGroup rail = new ToggleGroup();
  private final VBox railBox = new VBox(2);
  private final Button unfold = Icons.button("fth-chevrons-right", I18n.t("palette.unfold"),
      () -> userOpen(true));
  private final VBox center;
  private final Label noMatch = new Label();
  private final List<String> categoryNames = new ArrayList<>();
  private final List<ToggleButton> categoryButtons = new ArrayList<>();
  private VisualMode.PaletteContext context = VisualMode.PaletteContext.NONE;
  private boolean open = true;
  private Consumer<Boolean> onOpenChanged = shown -> { };
  /** The rail is being set from the list's scrolling (not a click). */
  private boolean following;
  /** The category last clicked: it stays current while its heading is in view. */
  private String pinned;

  BlockPalette(ApiIndex index, Consumer<ApiMethod> onInsert) {
    this.index = index;
    getStyleClass().add("block-palette");

    railBox.getStyleClass().add("category-rail");
    railBox.setAlignment(Pos.TOP_CENTER);
    unfold.getStyleClass().add("palette-unfold");
    rail.selectedToggleProperty().addListener((o, old, toggle) -> {
      if (toggle == null) {
        if (old != null) old.setSelected(true);
        return;
      }
      if (!following) {
        if (!open) userOpen(true);
        scrollTo((String) toggle.getUserData());
      }
    });

    list.getStyleClass().add("block-list");
    list.setCellFactory(view -> new BlockCell());
    list.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2 && selectedItem() != null) {
        onInsert.accept(selectedItem());
      }
    });
    list.skinProperty().addListener((o, old, skin) -> javafx.application.Platform.runLater(
        this::followScrolling));
    list.setPlaceholder(noMatch);
    noMatch.getStyleClass().add("palette-hint");
    noMatch.setWrapText(true);

    search.setPromptText(I18n.t("palette.search"));
    search.textProperty().addListener((o, old, v) -> refill());
    search.getStyleClass().add("palette-search");

    Label title = new Label(I18n.t("palette.title"));
    title.getStyleClass().add("palette-title");
    Region gap = new Region();
    HBox.setHgrow(gap, Priority.ALWAYS);
    Button fold = Icons.button("fth-chevrons-left", I18n.t("palette.fold"),
        () -> userOpen(false));
    HBox header = new HBox(4, title, gap, fold);
    header.setAlignment(Pos.CENTER_LEFT);
    header.getStyleClass().add("palette-header");

    Label hint = new Label(I18n.t("palette.hint"));
    hint.getStyleClass().add("palette-hint");
    hint.setWrapText(true);

    center = new VBox(6, header, search, list, hint);
    VBox.setVgrow(list, Priority.ALWAYS);
    center.getStyleClass().add("palette-center");
    var railScroll = new javafx.scene.control.ScrollPane(railBox);
    railScroll.setFitToWidth(true);
    railScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
    railScroll.getStyleClass().add("rail-scroll");
    setLeft(railScroll);
    setCenter(center);
    setMinWidth(Region.USE_PREF_SIZE);
    setMaxWidth(Region.USE_PREF_SIZE);
    refill();
    applyOpen();
  }

  /** Called when the student folds or unfolds the pane (not when the host does). */
  void setOnOpenChanged(Consumer<Boolean> action) {
    this.onOpenChanged = action;
  }

  /** Open: rail and blocks. Folded: only the rail, which unfolds it again. */
  void setOpen(boolean show) {
    if (show == open) return;
    open = show;
    applyOpen();
  }

  boolean isOpen() {
    return open;
  }

  private void userOpen(boolean show) {
    setOpen(show);
    onOpenChanged.accept(show);
  }

  private void applyOpen() {
    setCenter(open ? center : null);
    unfold.setVisible(!open);
    unfold.setManaged(!open);
    pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("folded"), !open);
  }

  /**
   * Only the blocks of the class being edited: a sprite gets no stage blocks
   * and a stage no sprite blocks; categories without blocks leave the rail.
   */
  void setContext(VisualMode.PaletteContext next) {
    if (next == context) return;
    context = next;
    refill();
  }

  VisualMode.PaletteContext context() {
    return context;
  }

  private boolean fits(ApiMethod m) {
    return switch (context) {
      case SPRITE -> !m.className().equals("Stage");
      case STAGE -> !m.className().equals("Sprite");
      case NONE -> true;
    };
  }

  /** The blocks listed now (tests). */
  List<ApiMethod> shownBlocks() {
    return list.getItems().stream().filter(e -> !e.isHeading()).map(Entry::method).toList();
  }

  private static String cssKey(String category) {
    return "cat-" + category.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
  }

  private static String categoryLabel(String category) {
    String key = "palette.category." + category.toLowerCase(Locale.ROOT);
    try {
      return I18n.t(key);
    } catch (java.util.MissingResourceException e) {
      return category;
    }
  }

  /** Scratch's order inside a category: hats, then stack blocks, then conditions and values. */
  private static int shape(ApiMethod m) {
    String text = blockText(m);
    if (text.startsWith("when ")) return 0;
    if (text.startsWith("<")) return 2;
    if (text.startsWith("(")) return 3;
    return 1;
  }

  void refresh() { refill(); }

  private void refill() {
    String query = search.getText() == null ? ""
        : search.getText().trim().toLowerCase(Locale.ROOT);
    var byCategory = index.blockPalette();
    List<String> names = new ArrayList<>(byCategory.keySet());
    names.sort(Comparator.comparingInt((String n) -> ORDER.contains(n) ? ORDER.indexOf(n) : 99)
        .thenComparing(Comparator.naturalOrder()));
    List<Entry> entries = new ArrayList<>();
    List<String> shownCategories = new ArrayList<>();
    for (String name : names) {
      // overloads and Sprite/Stage twins show the same block once
      java.util.Map<String, ApiMethod> unique = new java.util.LinkedHashMap<>();
      byCategory.get(name).stream()
          .filter(this::fits)
          .sorted(Comparator.comparingInt(BlockPalette::shape))
          .forEach(m -> unique.putIfAbsent(blockText(m) + "|" + snippet(m), m));
      boolean any = !unique.isEmpty();
      if (any) shownCategories.add(name);
      List<ApiMethod> matching = unique.values().stream()
          .filter(m -> query.isEmpty()
              || blockText(m).toLowerCase(Locale.ROOT).contains(query)
              || m.methodName().toLowerCase(Locale.ROOT).contains(query))
          .toList();
      if (!matching.isEmpty()) {
        entries.add(new Entry(name, null));
        matching.forEach(m -> entries.add(new Entry(name, m)));
      }
    }
    if (!shownCategories.equals(categoryNames)) buildRail(shownCategories);
    noMatch.setText(I18n.t("palette.nomatch", search.getText() == null ? ""
        : search.getText().trim()));
    list.getItems().setAll(entries);
    pinned = null;
    list.scrollTo(0);
    followScrolling();
  }

  private void buildRail(List<String> names) {
    String selected = rail.getSelectedToggle() == null ? null
        : (String) rail.getSelectedToggle().getUserData();
    categoryNames.clear();
    categoryNames.addAll(names);
    categoryButtons.forEach(b -> b.setToggleGroup(null));
    categoryButtons.clear();
    railBox.getChildren().setAll(unfold);
    for (String name : names) {
      ToggleButton button = new ToggleButton();
      Circle dot = new Circle(9);
      dot.getStyleClass().addAll("category-dot", cssKey(name));
      Label label = new Label(categoryLabel(name));
      label.getStyleClass().add("category-label");
      VBox content = new VBox(3, dot, label);
      content.setAlignment(Pos.CENTER);
      button.setGraphic(content);
      button.setUserData(name);
      button.getStyleClass().add("category-button");
      button.setMaxWidth(Double.MAX_VALUE);
      button.setTooltip(new Tooltip(categoryLabel(name)));
      railBox.getChildren().add(button);
      categoryButtons.add(button);
      button.setToggleGroup(rail);
    }
    following = true;
    try {
      categoryButtons.stream().filter(b -> b.getUserData().equals(selected)).findFirst()
          .or(() -> categoryButtons.stream().findFirst())
          .ifPresent(b -> b.setSelected(true));
    } finally {
      following = false;
    }
  }

  /** Scrolls the list to a category's heading (the rail's buttons). */
  void scrollTo(String category) {
    int heading = headingOf(category);
    if (heading >= 0) {
      pinned = category;
      list.scrollTo(heading);
      markRail(category);
    }
  }

  private int headingOf(String category) {
    for (int i = 0; i < list.getItems().size(); i++) {
      if (list.getItems().get(i).category().equals(category)) return i;
    }
    return -1;
  }

  /** The rail shows the category of the blocks at the top of the list. */
  private void followScrolling() {
    if (list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
      if (!Boolean.TRUE.equals(flow.getProperties().get("scratch4j.followed"))) {
        flow.getProperties().put("scratch4j.followed", true);
        flow.positionProperty().addListener((o, old, p) -> markTopCategory(flow));
      }
      markTopCategory(flow);
    }
  }

  private void markTopCategory(VirtualFlow<?> flow) {
    var first = flow.getFirstVisibleCell();
    if (first == null || first.getIndex() < 0 || first.getIndex() >= list.getItems().size()) {
      return;
    }
    if (pinned != null) {
      // the last categories cannot scroll to the top: the clicked one stays current
      int heading = headingOf(pinned);
      var last = flow.getLastVisibleCell();
      if (heading >= first.getIndex() && last != null && heading <= last.getIndex()) return;
      pinned = null;
    }
    markRail(list.getItems().get(first.getIndex()).category());
  }

  /** Shows a category as current on the rail without scrolling to it. */
  private void markRail(String category) {
    following = true;
    try {
      categoryButtons.stream().filter(b -> b.getUserData().equals(category)).findFirst()
          .ifPresent(b -> b.setSelected(true));
    } finally {
      following = false;
    }
  }

  /** The scratchblock text, HTML entities from the Javadoc decoded. */
  static String blockText(ApiMethod method) {
    return method.scratchblock() == null ? "" : method.scratchblock()
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'");
  }

  /** The Java code a block inserts: a call, or an override for event blocks. */
  static String snippet(ApiMethod method) {
    List<String> names = method.params().stream()
        .map(p -> p.contains(" ") ? p.substring(p.lastIndexOf(' ') + 1) : p)
        .toList();
    String returnType = method.returnType() == null ? "void" : method.returnType();
    boolean isStatic = returnType.startsWith("static ");
    String bareReturn = returnType.replace("static ", "");
    if (method.methodName().startsWith("when") && bareReturn.equals("void") && !isStatic) {
      return "public void " + method.methodName() + "(" + String.join(", ", method.params())
          + ") {\n  \n}\n";
    }
    String target = isStatic ? method.className() + "." : "this.";
    String call = target + method.methodName() + "(" + String.join(", ", names) + ")";
    return bareReturn.equals("void") ? call + ";\n" : call;
  }

  /** A block drawn Scratch-style: text plus round/square/boolean input slots. */
  static Node renderBlock(ApiMethod method) {
    String text = blockText(method);
    FlowPane block = new FlowPane(4, 2);
    block.setPrefWrapLength(210);
    block.setMaxWidth(Region.USE_PREF_SIZE);
    block.getStyleClass().addAll("block", cssKey(method.category()));
    boolean reporter = text.startsWith("(") && text.endsWith(")");
    boolean predicate = text.startsWith("<") && text.endsWith(">");
    if (reporter || predicate) {
      block.getStyleClass().add(reporter ? "reporter" : "predicate");
      text = text.substring(1, text.length() - 1);
    }
    if (text.startsWith("when ")) {
      block.getStyleClass().add("hat");
    }
    StringBuilder plain = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '(' || c == '[' || c == '<') {
        int end = closing(text, i);
        if (end > i) {
          flush(block, plain);
          String inner = text.substring(i + 1, end).trim();
          Label input = new Label(inner.endsWith(" v")
              ? inner.substring(0, inner.length() - 2) + " ▾" : inner);
          input.getStyleClass().add(c == '(' ? "block-input-round"
              : c == '[' ? "block-input-square" : "block-input-bool");
          block.getChildren().add(input);
          i = end + 1;
          continue;
        }
      }
      plain.append(c);
      i++;
    }
    flush(block, plain);
    return block;
  }

  private static void flush(FlowPane block, StringBuilder plain) {
    String text = plain.toString().trim();
    if (!text.isEmpty()) {
      Label label = new Label(text);
      label.getStyleClass().add("block-text");
      block.getChildren().add(label);
    }
    plain.setLength(0);
  }

  private static int closing(String text, int open) {
    char o = text.charAt(open);
    char c = o == '(' ? ')' : o == '[' ? ']' : '>';
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      if (text.charAt(i) == o) {
        depth++;
      } else if (text.charAt(i) == c) {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    return -1;
  }

  private final class BlockCell extends ListCell<Entry> {

    BlockCell() {
      // never wider than the list: no horizontal scrolling
      setPrefWidth(0);
      setOnDragDetected(e -> {
        if (getItem() == null || getItem().isHeading()) {
          return;
        }
        var board = startDragAndDrop(TransferMode.COPY);
        ClipboardContent content = new ClipboardContent();
        content.putString(snippet(getItem().method()));
        board.setContent(content);
        board.setDragView(snapshot(null, null));
        e.consume();
      });
    }

    @Override
    protected void updateItem(Entry entry, boolean empty) {
      super.updateItem(entry, empty);
      getStyleClass().remove("block-heading-cell");
      if (empty || entry == null) {
        setText(null);
        setGraphic(null);
        setTooltip(null);
        setMouseTransparent(false);
        return;
      }
      if (entry.isHeading()) {
        Circle dot = new Circle(6);
        dot.getStyleClass().addAll("category-dot", cssKey(entry.category()));
        Label heading = new Label(categoryLabel(entry.category()), dot);
        heading.getStyleClass().add("block-heading");
        getStyleClass().add("block-heading-cell");
        setText(null);
        setGraphic(heading);
        setTooltip(null);
        // a heading is not a block: clicks pass to the list without selecting it
        setMouseTransparent(true);
        return;
      }
      setMouseTransparent(false);
      ApiMethod method = entry.method();
      Node block = renderBlock(method);
      Label java = new Label(snippet(method).lines().findFirst().orElse(""));
      java.getStyleClass().add("block-java");
      VBox box = new VBox(4, block, java);
      HBox.setHgrow(box, Priority.ALWAYS);
      setText(null);
      setGraphic(box);
      String summary = method.summary() == null ? "" : method.summary() + "\n\n";
      setTooltip(new Tooltip(summary + method.className() + " · " + method.signature()
          + "\n" + I18n.t("palette.hint")));
    }
  }

  /** The categories on the rail, for tests. */
  List<String> categoryItems() {
    return List.copyOf(categoryNames);
  }

  /** The category the rail shows as current (tests). */
  String currentCategory() {
    return rail.getSelectedToggle() == null ? null
        : (String) rail.getSelectedToggle().getUserData();
  }

  ApiMethod selectedItem() {
    Entry entry = list.getSelectionModel().getSelectedItem();
    return entry == null || entry.isHeading() ? null : entry.method();
  }

  void selectFirst() {
    for (int i = 0; i < list.getItems().size(); i++) {
      if (!list.getItems().get(i).isHeading()) {
        list.getSelectionModel().select(i);
        return;
      }
    }
  }
}
