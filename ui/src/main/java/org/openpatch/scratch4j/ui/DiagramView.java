package org.openpatch.scratch4j.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.uml.ClassModel;
import org.openpatch.scratch4j.runner.Debugger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Diagrams of the project: the UML class diagram generated from the code
 * (attributes, methods, inheritance, associations), and the object diagram of
 * the running program (which objects exist, their values, who references whom).
 */
final class DiagramView extends BorderPane {

  private final ScratchProject project;
  private final Supplier<Debugger> session;
  private final Consumer<Path> openFile;
  private final UmlCanvas canvas = new UmlCanvas();
  private final ToggleButton classesMode = new ToggleButton(I18n.t("diagram.classes"),
      Icons.of("fth-layout"));
  private final ToggleButton objectsMode = new ToggleButton(I18n.t("diagram.objects"),
      Icons.of("fth-box"));
  private final CheckBox attributes = new CheckBox(I18n.t("diagram.attributes"));
  private final CheckBox methods = new CheckBox(I18n.t("diagram.methods"));
  private final CheckBox library = new CheckBox(I18n.t("diagram.library"));
  private final CheckBox live = new CheckBox(I18n.t("diagram.live"));
  private final Button snapshot = new Button(I18n.t("diagram.snapshot"), Icons.of("fth-camera"));
  private final Label message = new Label();
  private final StackPane center;
  private final Timeline liveTimer;
  private ClassModel.Model model;
  private boolean reading;

  DiagramView(ScratchProject project, Supplier<Debugger> session, Consumer<Path> openFile) {
    this.project = project;
    this.session = session;
    this.openFile = openFile;
    getStyleClass().add("diagram-view");

    ToggleGroup mode = new ToggleGroup();
    classesMode.setToggleGroup(mode);
    objectsMode.setToggleGroup(mode);
    classesMode.setSelected(true);
    mode.selectedToggleProperty().addListener((o, old, now) -> {
      if (now == null) {
        old.setSelected(true);
        return;
      }
      refresh();
    });
    attributes.setSelected(true);
    methods.setSelected(true);
    library.setSelected(true);
    for (CheckBox box : List.of(attributes, methods, library)) {
      box.setOnAction(e -> refresh());
    }
    snapshot.setOnAction(e -> readObjects());
    live.setTooltip(new javafx.scene.control.Tooltip(I18n.t("diagram.live.tooltip")));
    liveTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
      if (live.isSelected() && objectsMode.isSelected()) readObjects();
    }));
    liveTimer.setCycleCount(Timeline.INDEFINITE);
    sceneProperty().addListener((o, old, scene) -> {
      if (scene == null) liveTimer.stop(); else liveTimer.play();
    });
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    Button export = Icons.labeled("fth-image", I18n.t("diagram.export"), this::exportPng);
    HBox bar = new HBox(6, classesMode, objectsMode, new Separator(
        javafx.geometry.Orientation.VERTICAL), attributes, methods, library, snapshot, live,
        spacer,
        Icons.button("fth-zoom-out", I18n.t("designer.zoomout"),
            () -> canvas.setZoom(canvas.zoom() / 1.25)),
        Icons.button("fth-zoom-in", I18n.t("designer.zoomin"),
            () -> canvas.setZoom(canvas.zoom() * 1.25)),
        export);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("tool-bar-row");
    bar.setPadding(new Insets(4, 8, 4, 8));
    setTop(bar);

    ScrollPane scroll = new ScrollPane(canvas);
    scroll.getStyleClass().add("paint-scroll");
    scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
      if (e.isShortcutDown() && e.getDeltaY() != 0) {
        canvas.setZoom(canvas.zoom() * (e.getDeltaY() > 0 ? 1.15 : 1 / 1.15));
        e.consume();
      }
    });
    message.setWrapText(true);
    message.getStyleClass().add("diagram-message");
    message.setMaxWidth(520);
    center = new StackPane(scroll, message);
    StackPane.setAlignment(message, Pos.TOP_CENTER);
    StackPane.setMargin(message, new Insets(24));
    setCenter(center);
    Label hint = new Label(I18n.t("diagram.hint"));
    hint.getStyleClass().add("editor-status");
    setBottom(hint);
    canvas.setOnOpen(this::open);
    refresh();
  }

  /** Re-reads the code (after saves) or the running program. */
  void refresh() {
    boolean objects = objectsMode.isSelected();
    attributes.setVisible(!objects);
    methods.setVisible(!objects);
    library.setVisible(!objects);
    attributes.setManaged(!objects);
    methods.setManaged(!objects);
    library.setManaged(!objects);
    snapshot.setVisible(objects);
    live.setVisible(objects);
    snapshot.setManaged(objects);
    live.setManaged(objects);
    if (objects) {
      readObjects();
    } else {
      showClasses();
    }
  }

  private void open(String id) {
    String className = objectsMode.isSelected() ? classOfObject.get(id) : id;
    if (className == null) return;
    try {
      Path file = project.sourceOf(className);
      if (file != null) openFile.accept(file);
    } catch (IOException ignored) {
      // nothing to open
    }
  }

  // --- class diagram ---------------------------------------------------------------------

  void showClasses() {
    try {
      model = ClassModel.of(project.javaSources());
    } catch (IOException | RuntimeException e) {
      message.setText(I18n.t("diagram.error", e.getMessage()));
      message.setVisible(true);
      return;
    }
    message.setVisible(model.classes().isEmpty());
    message.setText(I18n.t("diagram.noclasses"));
    Map<String, Integer> depth = new HashMap<>();
    List<UmlCanvas.Box> boxes = new ArrayList<>();
    List<UmlCanvas.Edge> edges = new ArrayList<>();
    for (ClassModel.UmlClass c : model.classes()) {
      if (c.library() && !library.isSelected()) continue;
      List<List<String>> sections = new ArrayList<>();
      if (!c.library()) {
        if (attributes.isSelected()) {
          sections.add(c.attributes().stream().map(ClassModel.Attribute::uml).toList());
        }
        if (methods.isSelected()) {
          sections.add(c.operations().stream().map(ClassModel.Operation::uml).toList());
        }
      }
      String title = (c.isInterface() ? "«interface» " : "") + c.name();
      boxes.add(new UmlCanvas.Box(c.name(), title, c.isAbstract() || c.isInterface(), false,
          c.library(), sections, depth(c, depth)));
      if (c.superclass() != null && model.byName(c.superclass()) != null
          && (library.isSelected() || !model.byName(c.superclass()).library())) {
        edges.add(new UmlCanvas.Edge(c.name(), c.superclass(),
            UmlCanvas.EdgeKind.INHERITANCE, ""));
      }
    }
    for (ClassModel.Association a : model.associations()) {
      edges.add(new UmlCanvas.Edge(a.from(), a.to(), UmlCanvas.EdgeKind.ASSOCIATION,
          a.role() + (a.many() ? " *" : "")));
    }
    canvas.show(boxes, edges);
  }

  /** Inheritance depth: library base classes on top, then their subclasses. */
  private int depth(ClassModel.UmlClass c, Map<String, Integer> memo) {
    Integer known = memo.get(c.name());
    if (known != null) return known;
    memo.put(c.name(), 0);
    ClassModel.UmlClass parent = c.superclass() == null ? null : model.byName(c.superclass());
    int d = 0;
    if (parent != null && (library.isSelected() || !parent.library())) {
      d = depth(parent, memo) + 1;
    }
    memo.put(c.name(), d);
    return d;
  }

  // --- object diagram ----------------------------------------------------------------------

  private final Map<String, String> classOfObject = new HashMap<>();

  void readObjects() {
    Debugger connection = session.get();
    if (connection == null || !connection.isAttached()) {
      canvas.show(List.of(), List.of());
      message.setText(I18n.t("diagram.notrunning"));
      message.setVisible(true);
      return;
    }
    if (reading) return;
    reading = true;
    Set<String> names = new HashSet<>();
    try {
      for (Path source : project.javaSources()) {
        names.add(source.getFileName().toString().replaceFirst("\\.java$", ""));
      }
    } catch (IOException e) {
      reading = false;
      return;
    }
    Thread worker = new Thread(() -> {
      List<Debugger.ObjectNode> objects;
      String error = null;
      try {
        objects = connection.objects(names, 60);
      } catch (RuntimeException e) {
        objects = List.of();
        error = String.valueOf(e.getMessage());
      }
      List<Debugger.ObjectNode> result = objects;
      String failed = error;
      Platform.runLater(() -> {
        reading = false;
        showObjects(result, failed);
      });
    }, "object-diagram");
    worker.setDaemon(true);
    worker.start();
  }

  void showObjects(List<Debugger.ObjectNode> objects, String error) {
    classOfObject.clear();
    if (error != null) {
      message.setText(I18n.t("diagram.error", error));
      message.setVisible(true);
      canvas.show(List.of(), List.of());
      return;
    }
    message.setVisible(objects.isEmpty());
    message.setText(I18n.t("diagram.noobjects"));
    // names: the attribute that references an object ("player : Player")
    Map<Long, String> name = new HashMap<>();
    Map<Long, Set<Long>> children = new HashMap<>();
    Set<Long> referenced = new HashSet<>();
    for (Debugger.ObjectNode o : objects) {
      for (Debugger.Reference r : o.references()) {
        name.putIfAbsent(r.target(), r.role());
        if (r.target() != o.id()) {
          children.computeIfAbsent(o.id(), k -> new LinkedHashSet<>()).add(r.target());
          referenced.add(r.target());
        }
      }
    }
    // layers: breadth-first from objects nobody references (usually the stage)
    Map<Long, Integer> layer = new HashMap<>();
    Deque<Long> queue = new ArrayDeque<>();
    List<Debugger.ObjectNode> sorted = new ArrayList<>(objects);
    sorted.sort((a, b) -> Boolean.compare(referenced.contains(a.id()),
        referenced.contains(b.id())));
    for (Debugger.ObjectNode o : sorted) {
      if (!referenced.contains(o.id())) {
        layer.put(o.id(), 0);
        queue.add(o.id());
      }
    }
    while (!queue.isEmpty()) {
      long id = queue.poll();
      for (long child : children.getOrDefault(id, Set.of())) {
        if (!layer.containsKey(child)) {
          layer.put(child, layer.get(id) + 1);
          queue.add(child);
        }
      }
    }
    List<UmlCanvas.Box> boxes = new ArrayList<>();
    List<UmlCanvas.Edge> edges = new ArrayList<>();
    for (Debugger.ObjectNode o : objects) {
      String id = String.valueOf(o.id());
      classOfObject.put(id, o.className());
      String title = name.getOrDefault(o.id(), "") + " : " + o.className();
      List<String> values = o.values().stream()
          .map(v -> v.name() + " = " + v.value()).toList();
      boxes.add(new UmlCanvas.Box(id, title.trim(), false, true, false,
          values.isEmpty() ? List.of() : List.of(values), layer.getOrDefault(o.id(), 0)));
      for (Debugger.Reference r : o.references()) {
        edges.add(new UmlCanvas.Edge(id, String.valueOf(r.target()),
            UmlCanvas.EdgeKind.ASSOCIATION, r.role()));
      }
    }
    canvas.show(boxes, edges);
  }

  private void exportPng() {
    javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
    chooser.setTitle(I18n.t("diagram.export"));
    chooser.setInitialFileName(objectsMode.isSelected() ? "objects.png" : "classes.png");
    chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("PNG",
        "*.png"));
    chooser.setInitialDirectory(project.root().toFile());
    java.io.File target = chooser.showSaveDialog(getScene().getWindow());
    if (target == null) return;
    try {
      writePng(target.toPath());
    } catch (IOException e) {
      new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
          e.getMessage()).showAndWait();
    }
  }

  /** The diagram as a PNG (worksheets, presentations). */
  void writePng(Path file) throws IOException {
    var image = canvas.snapshot(null, null);
    var buffered = new java.awt.image.BufferedImage((int) image.getWidth(),
        (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
    var reader = image.getPixelReader();
    for (int y = 0; y < buffered.getHeight(); y++) {
      for (int x = 0; x < buffered.getWidth(); x++) {
        buffered.setRGB(x, y, reader.getArgb(x, y));
      }
    }
    javax.imageio.ImageIO.write(buffered, "png", file.toFile());
  }

  UmlCanvas canvas() {
    return canvas;
  }

  void showObjectsMode() {
    objectsMode.setSelected(true);
  }
}
