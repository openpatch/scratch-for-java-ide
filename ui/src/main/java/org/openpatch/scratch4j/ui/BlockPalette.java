package org.openpatch.scratch4j.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
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
 * The Scratch-style block palette: a rail of coloured categories (as in
 * Scratch) and the blocks of the chosen category, drawn as Scratch blocks
 * with their Java call underneath. Double-click or drag into the editor
 * inserts the Java code; event blocks ({@code when ...}) insert a method
 * override instead of a call.
 */
final class BlockPalette extends BorderPane {

  /** Scratch's own category order; other categories follow alphabetically. */
  private static final List<String> ORDER = List.of("Motion", "Looks", "Sound", "Events",
      "Control", "Sensing", "Operators", "Pen", "Clock");

  private final ApiIndex index;
  private final ListView<ApiMethod> list = new ListView<>();
  private final TextField search = new TextField();
  private final ToggleGroup rail = new ToggleGroup();
  private final List<String> categoryNames = new ArrayList<>();
  private String category;
  private VisualMode.PaletteContext context = VisualMode.PaletteContext.NONE;
  private final List<ToggleButton> categoryButtons = new ArrayList<>();

  BlockPalette(ApiIndex index, Consumer<ApiMethod> onInsert) {
    this.index = index;
    getStyleClass().add("block-palette");

    List<String> names = new ArrayList<>(index.blockPalette().keySet());
    names.sort(Comparator.comparingInt((String n) -> ORDER.contains(n) ? ORDER.indexOf(n) : 99)
        .thenComparing(Comparator.naturalOrder()));
    categoryNames.add(I18n.t("palette.category.all"));
    categoryNames.addAll(names);

    VBox railBox = new VBox(2);
    railBox.getStyleClass().add("category-rail");
    for (String name : categoryNames) {
      ToggleButton button = new ToggleButton();
      Circle dot = new Circle(9);
      dot.getStyleClass().addAll("category-dot", cssKey(name));
      Label label = new Label(categoryLabel(name));
      label.getStyleClass().add("category-label");
      VBox content = new VBox(2, dot, label);
      content.setAlignment(Pos.CENTER);
      button.setGraphic(content);
      button.setToggleGroup(rail);
      button.setUserData(name);
      button.getStyleClass().add("category-button");
      button.setMaxWidth(Double.MAX_VALUE);
      railBox.getChildren().add(button);
      categoryButtons.add(button);
    }
    rail.selectedToggleProperty().addListener((o, old, toggle) -> {
      if (toggle == null) {
        old.setSelected(true);
        return;
      }
      category = (String) toggle.getUserData();
      refill();
    });

    list.getStyleClass().add("block-list");
    list.setCellFactory(view -> new BlockCell());
    list.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
        onInsert.accept(list.getSelectionModel().getSelectedItem());
      }
    });

    search.setPromptText(I18n.t("palette.search"));
    search.textProperty().addListener((o, old, v) -> refill());
    search.getStyleClass().add("palette-search");

    Label hint = new Label(I18n.t("palette.hint"));
    hint.getStyleClass().add("palette-hint");
    hint.setWrapText(true);

    VBox center = new VBox(6, search, list, hint);
    VBox.setVgrow(list, Priority.ALWAYS);
    center.getStyleClass().add("palette-center");
    var railScroll = new javafx.scene.control.ScrollPane(railBox);
    railScroll.setFitToWidth(true);
    railScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
    railScroll.getStyleClass().add("rail-scroll");
    setLeft(railScroll);
    setCenter(center);
    setPrefWidth(340);
    ((ToggleButton) railBox.getChildren().get(0)).setSelected(true);
  }

  /**
   * Only the blocks of the class being edited: a sprite gets no stage blocks
   * and a stage no sprite blocks; categories without blocks leave the rail.
   */
  void setContext(VisualMode.PaletteContext next) {
    if (next == context) return;
    context = next;
    ToggleButton selected = (ToggleButton) rail.getSelectedToggle();
    for (int i = 1; i < categoryButtons.size(); i++) {
      ToggleButton button = categoryButtons.get(i);
      String name = (String) button.getUserData();
      boolean any = index.blockPalette().getOrDefault(name, List.of()).stream()
          .anyMatch(this::fits);
      button.setVisible(any);
      button.setManaged(any);
    }
    if (selected != null && !selected.isVisible()) {
      categoryButtons.get(0).setSelected(true);
    }
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
    return List.copyOf(list.getItems());
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

  void refresh() { refill(); }

  private void refill() {
    String query = search.getText() == null ? ""
        : search.getText().trim().toLowerCase(Locale.ROOT);
    List<ApiMethod> entries;
    if (category == null || category.equals(categoryNames.get(0)) || !query.isEmpty()) {
      entries = index.methods().stream()
          .filter(m -> m.scratchblock() != null)
          .sorted(Comparator.comparingInt((ApiMethod m) -> ORDER.contains(m.category())
                  ? ORDER.indexOf(m.category()) : 99)
              .thenComparing(ApiMethod::category)
              .thenComparing(ApiMethod::methodName))
          .toList();
    } else {
      entries = index.blockPalette().getOrDefault(category, List.of());
    }
    entries = entries.stream().filter(this::fits).toList();
    if (!query.isEmpty()) {
      entries = entries.stream()
          .filter(m -> blockText(m).toLowerCase(Locale.ROOT).contains(query)
              || m.methodName().toLowerCase(Locale.ROOT).contains(query))
          .toList();
    }
    // overloads and Sprite/Stage twins show the same block once
    java.util.Map<String, ApiMethod> unique = new java.util.LinkedHashMap<>();
    for (ApiMethod m : entries) {
      unique.putIfAbsent(blockText(m) + "|" + snippet(m), m);
    }
    list.getItems().setAll(unique.values());
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

  private final class BlockCell extends ListCell<ApiMethod> {

    BlockCell() {
      // never wider than the list: no horizontal scrolling
      setPrefWidth(0);
      setOnDragDetected(e -> {
        if (getItem() == null) {
          return;
        }
        var board = startDragAndDrop(TransferMode.COPY);
        ClipboardContent content = new ClipboardContent();
        content.putString(snippet(getItem()));
        board.setContent(content);
        board.setDragView(snapshot(null, null));
        e.consume();
      });
    }

    @Override
    protected void updateItem(ApiMethod method, boolean empty) {
      super.updateItem(method, empty);
      if (empty || method == null) {
        setText(null);
        setGraphic(null);
        setTooltip(null);
        return;
      }
      Node block = renderBlock(method);
      Label java = new Label(snippet(method).lines().findFirst().orElse(""));
      java.getStyleClass().add("block-java");
      VBox box = new VBox(3, block, java);
      HBox.setHgrow(box, Priority.ALWAYS);
      setText(null);
      setGraphic(box);
      String summary = method.summary() == null ? "" : method.summary() + "\n\n";
      setTooltip(new Tooltip(summary + method.className() + " · " + method.signature()
          + "\n" + I18n.t("palette.hint")));
    }
  }

  /** The category names shown, for tests. */
  List<String> categoryItems() {
    return categoryNames;
  }

  ApiMethod selectedItem() {
    return list.getSelectionModel().getSelectedItem();
  }

  void selectFirst() {
    list.getSelectionModel().selectFirst();
  }
}
