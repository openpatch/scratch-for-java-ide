package org.openpatch.scratch4j.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import org.openpatch.scratch4j.runner.ProgramState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The variables of the running program, live: the stage, every sprite (its
 * own variables, then position, direction, costume) and the shared
 * ({@code static}) variables, like Scratch's variable monitors. A ticked
 * "On stage" box pins a value onto the running stage.
 */
final class VariablesView extends BorderPane {

  /** Pins a value onto the stage (true) or removes it: owner id, field, label. */
  interface PinHandler {
    void pin(String owner, String field, String label, boolean on);
  }

  /** One row: a group (stage, sprite) or a value that can be pinned. */
  static final class Row {
    final StringProperty name = new SimpleStringProperty();
    final StringProperty value = new SimpleStringProperty("");
    final BooleanProperty pinned = new SimpleBooleanProperty();
    /** Null for groups. */
    final String owner;
    final String field;
    String label;

    Row(String owner, String field) {
      this.owner = owner;
      this.field = field;
    }
  }

  private static final List<String> PROPS =
      List.of("@x", "@y", "@direction", "@size", "@costume", "@visible");

  private final Label state = new Label(I18n.t("variables.idle"));
  private final TreeTableView<Row> table = new TreeTableView<>();
  private final TreeItem<Row> root = new TreeItem<>(new Row(null, null));
  /** Every row by key, reused between reports so expansion and selection stay. */
  private final Map<String, TreeItem<Row>> items = new HashMap<>();
  private final Set<String> pins = new HashSet<>();
  private final PinHandler onPin;

  VariablesView(PinHandler onPin) {
    this.onPin = onPin;
    getStyleClass().add("variables-pane");
    state.getStyleClass().add("variables-state");
    state.setPadding(new Insets(4, 8, 4, 8));
    setTop(state);

    TreeTableColumn<Row, String> name = new TreeTableColumn<>(I18n.t("variables.name"));
    name.setCellValueFactory(c -> c.getValue().getValue().name);
    name.setPrefWidth(220);
    TreeTableColumn<Row, String> value = new TreeTableColumn<>(I18n.t("variables.value"));
    value.setCellValueFactory(c -> c.getValue().getValue().value);
    value.setPrefWidth(320);
    TreeTableColumn<Row, Boolean> pin = new TreeTableColumn<>(I18n.t("variables.pin"));
    pin.setPrefWidth(80);
    pin.setCellValueFactory(c -> c.getValue().getValue().pinned.asObject());
    pin.setCellFactory(column -> new PinCell());
    table.getColumns().add(name);
    table.getColumns().add(value);
    table.getColumns().add(pin);
    table.setRoot(root);
    table.setShowRoot(false);
    table.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(new Label(I18n.t("variables.idle")));
    setCenter(table);
  }

  /** The "On stage" box: only values have one, groups do not. */
  private final class PinCell extends TreeTableCell<Row, Boolean> {
    private final CheckBox box = new CheckBox();

    PinCell() {
      box.setTooltip(new Tooltip(I18n.t("variables.pin.tooltip")));
      box.setOnAction(e -> {
        TreeItem<Row> item = getTableRow() == null ? null : getTableRow().getTreeItem();
        if (item != null) {
          setPinned(item.getValue(), box.isSelected());
        }
      });
    }

    @Override
    protected void updateItem(Boolean pinned, boolean empty) {
      super.updateItem(pinned, empty);
      TreeItem<Row> item = getTableRow() == null ? null : getTableRow().getTreeItem();
      if (empty || item == null || item.getValue().owner == null) {
        setGraphic(null);
        return;
      }
      box.setSelected(Boolean.TRUE.equals(pinned));
      setGraphic(box);
    }
  }

  private void setPinned(Row row, boolean on) {
    String key = row.owner + " " + row.field;
    if (on ? pins.add(key) : pins.remove(key)) {
      row.pinned.set(on);
      onPin.pin(row.owner, row.field, row.label, on);
    }
  }

  TreeItem<Row> rootForTests() {
    return root;
  }

  void pinForTests(Row row, boolean on) {
    setPinned(row, on);
  }

  /** A new run: other objects, so no rows and no pins carry over. */
  void reset() {
    items.clear();
    pins.clear();
    root.getChildren().clear();
    state.setText(I18n.t("variables.waiting"));
  }

  /** The program ended: the last values stay, to look at after a crash. */
  void ended() {
    if (!root.getChildren().isEmpty()) {
      state.setText(I18n.t("variables.ended"));
    } else {
      state.setText(I18n.t("variables.idle"));
    }
  }

  void show(ProgramState program) {
    state.setText(I18n.t(program.paused() ? "variables.paused" : "variables.running",
        String.valueOf(program.frame())));
    // sprites are named like Scratch's clones: Cat 1, Cat 2, ...
    Map<String, String> labels = new HashMap<>();
    Map<String, Integer> perClass = new HashMap<>();
    for (ProgramState.Entry sprite : program.sprites()) {
      int n = perClass.merge(sprite.className(), 1, Integer::sum);
      labels.put(sprite.id(), sprite.className() + " " + n);
    }
    if (program.stage() != null) {
      labels.put(program.stage().id(), program.stage().className());
    }

    List<TreeItem<Row>> top = new ArrayList<>();
    if (program.stage() != null) {
      ProgramState.Entry stage = program.stage();
      TreeItem<Row> item = group("stage", I18n.t("variables.stage") + ": " + stage.className(),
          "", true);
      setChildren(item, values(stage, stage.className(), labels));
      top.add(item);
    }
    List<TreeItem<Row>> shared = new ArrayList<>();
    for (ProgramState.Entry type : program.statics()) {
      for (ProgramState.Value v : type.fields()) {
        TreeItem<Row> item = value(type.id(), v.name(), type.className() + "." + v.name(),
            type.className() + "." + v.name(), display(v.value(), labels));
        shared.add(item);
      }
    }
    if (!shared.isEmpty()) {
      TreeItem<Row> item = group("shared", I18n.t("variables.shared"), "", true);
      setChildren(item, shared);
      top.add(item);
    }
    boolean expandSprites = program.sprites().size() <= 5;
    for (ProgramState.Entry sprite : program.sprites()) {
      String label = labels.get(sprite.id());
      TreeItem<Row> item = group(sprite.id(), label, summary(sprite), expandSprites);
      setChildren(item, values(sprite, label, labels));
      top.add(item);
    }
    if (program.total() > program.sprites().size()) {
      top.add(group("more", I18n.t("variables.more",
          String.valueOf(program.total() - program.sprites().size())), "", false));
    }
    setChildren(root, top);
  }

  /** The student's own variables first, then the library's properties. */
  private List<TreeItem<Row>> values(ProgramState.Entry entry, String label,
      Map<String, String> labels) {
    List<TreeItem<Row>> rows = new ArrayList<>();
    for (ProgramState.Value v : entry.fields()) {
      rows.add(value(entry.id(), v.name(), v.name(), label + ": " + v.name(),
          display(v.value(), labels)));
    }
    for (ProgramState.Value v : entry.props()) {
      String name = I18n.t("variables.prop." + v.name().substring(1));
      rows.add(value(entry.id(), v.name(), name, label + ": " + name, v.value()));
    }
    return rows;
  }

  private TreeItem<Row> group(String key, String name, String value, boolean expanded) {
    TreeItem<Row> item = items.get("group " + key);
    if (item == null) {
      item = new TreeItem<>(new Row(null, null));
      item.setExpanded(expanded);
      items.put("group " + key, item);
    }
    item.getValue().name.set(name);
    item.getValue().value.set(value);
    return item;
  }

  private TreeItem<Row> value(String owner, String field, String name, String label,
      String value) {
    String key = owner + " " + field;
    TreeItem<Row> item = items.computeIfAbsent(key, k -> new TreeItem<>(new Row(owner, field)));
    Row row = item.getValue();
    row.name.set(name);
    row.value.set(value);
    row.label = label;
    row.pinned.set(pins.contains(key));
    return item;
  }

  /** Replaces children only when they changed: keeps the selection and scroll position. */
  private static void setChildren(TreeItem<Row> parent, List<TreeItem<Row>> children) {
    if (!parent.getChildren().equals(children)) {
      parent.getChildren().setAll(children);
    }
  }

  private static String summary(ProgramState.Entry sprite) {
    Map<String, String> props = new HashMap<>();
    for (ProgramState.Value v : sprite.props()) {
      props.put(v.name(), v.value());
    }
    if (!props.keySet().containsAll(PROPS.subList(0, 3))) return "";
    String text = "x " + props.get("@x") + ", y " + props.get("@y") + ", "
        + props.get("@direction") + "°";
    return "false".equals(props.get("@visible")) ? text + " (" + I18n.t("variables.hidden") + ")"
        : text;
  }

  /** Another sprite by its name in this list ("Cat 2"), not by its id. */
  static String display(String value, Map<String, String> labels) {
    if (!value.startsWith(ProgramState.REF)) return value;
    String[] parts = value.substring(ProgramState.REF.length()).split(":", 2);
    String label = labels.get(parts[0]);
    return label != null ? label : parts.length > 1 ? parts[1] : value;
  }
}
