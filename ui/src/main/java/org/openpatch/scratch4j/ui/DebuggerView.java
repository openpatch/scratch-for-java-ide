package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.openpatch.scratch4j.runner.Debugger;

import java.util.ArrayList;
import java.util.List;

/**
 * The debugger tab: where the program paused, its variables (locals, then the
 * object's fields) and the buttons a beginner needs.
 */
final class DebuggerView extends BorderPane {

  /** Table row (JavaFX properties for the columns). */
  public static final class Row {
    private final String name;
    private final String type;
    private final String value;

    Row(String name, String type, String value) {
      this.name = name;
      this.type = type;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public String getType() {
      return type;
    }

    public String getValue() {
      return value;
    }
  }

  private final Label place = new Label();
  private final TableView<Row> table = new TableView<>();
  private final Button resume;
  private final Button stepOver;
  private final Button stepInto;
  private final Button stepOut;
  private Debugger debugger;

  DebuggerView(Runnable onStop) {
    getStyleClass().add("debugger-pane");
    place.getStyleClass().add("debug-place");
    resume = Icons.labeled("fth-play", I18n.t("debug.continue"), () -> debugger.resume());
    stepOver = Icons.labeled("fth-corner-down-right", I18n.t("debug.over"),
        () -> debugger.stepOver());
    stepInto = Icons.labeled("fth-arrow-down-right", I18n.t("debug.into"),
        () -> debugger.stepInto());
    stepOut = Icons.labeled("fth-arrow-up-left", I18n.t("debug.out"), () -> debugger.stepOut());
    Button stop = Icons.labeled("fth-octagon", I18n.t("debug.stop"), onStop);
    HBox bar = new HBox(6, resume, stepOver, stepInto, stepOut, stop, place);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.setPadding(new Insets(4, 8, 4, 8));
    setTop(bar);
    TableColumn<Row, String> name = new TableColumn<>(I18n.t("debug.name"));
    name.setCellValueFactory(new PropertyValueFactory<>("name"));
    TableColumn<Row, String> type = new TableColumn<>(I18n.t("debug.type"));
    type.setCellValueFactory(new PropertyValueFactory<>("type"));
    TableColumn<Row, String> value = new TableColumn<>(I18n.t("debug.value"));
    value.setCellValueFactory(new PropertyValueFactory<>("value"));
    name.setPrefWidth(180);
    type.setPrefWidth(120);
    value.setPrefWidth(320);
    table.getColumns().add(name);
    table.getColumns().add(type);
    table.getColumns().add(value);
    table.setPlaceholder(new Label(I18n.t("debug.hint")));
    setCenter(table);
    running(null);
  }

  void attach(Debugger debugger) {
    this.debugger = debugger;
    running(I18n.t("debug.running"));
  }

  void paused(Debugger.Pause pause) {
    List<Row> rows = new ArrayList<>();
    for (Debugger.Variable v : pause.locals()) {
      rows.add(new Row(v.name(), v.type(), v.value()));
    }
    for (Debugger.Variable v : pause.fields()) {
      rows.add(new Row("this." + v.name(), v.type(), v.value()));
    }
    table.getItems().setAll(rows);
    place.setText(I18n.t("debug.paused", pause.sourceFile(), pause.line(), pause.method()));
    setButtons(true);
  }

  /** Running (text) or idle (null): the step buttons only work while paused. */
  void running(String text) {
    table.getItems().clear();
    place.setText(text == null ? I18n.t("debug.idle") : text);
    setButtons(false);
  }

  private void setButtons(boolean paused) {
    for (Button b : List.of(resume, stepOver, stepInto, stepOut)) {
      b.setDisable(!paused || debugger == null);
    }
  }
}
