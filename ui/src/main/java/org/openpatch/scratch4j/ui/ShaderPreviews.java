package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.ShaderUses;
import org.openpatch.scratch4j.core.region.SpriteLook;
import org.openpatch.scratch4j.runner.ShaderRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Previews of shaders: what a class's costume (a sprite) or backdrop (a
 * stage) looks like through the shader it adds, with the uniforms its code
 * sets to numbers. Rendered off-screen by {@link ShaderRenderer} (OpenGL, no
 * window), animated when the shader uses {@code time}. Shown in the gutter of
 * a {@code getShaders().add(...)} line and beside an open {@code .frag}/{@code .vert}.
 */
final class ShaderPreviews {

  /** The largest side of a preview. */
  static final int SIZE = 220;
  /** The first render: one picture; a shader using time then runs live ({@link LiveLoop}). */
  static final int FRAMES = 1;
  static final double SECONDS = 1;

  private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "shader-previews");
    t.setDaemon(true);
    return t;
  });
  private static final Map<String, CompletableFuture<Rendered>> GUTTER = new ConcurrentHashMap<>();
  private static final Pattern ADD = Pattern.compile("getShaders\\(\\)\\.add\\(");
  private static final Pattern BACKDROP = Pattern.compile(
      "\\baddBackdrop\\(\\s*\"[^\"]*\"\\s*,\\s*\"([^\"]+)\"");

  /** Frames as images, or the errors; {@code seconds} for one round of the animation. */
  record Rendered(List<Image> frames, List<ShaderRenderer.Error> errors, String failure,
      List<String> unset, List<ShaderRenderer.Error> warnings, Map<String, String> types,
      ShaderRenderer.Request request) {
    Rendered(List<Image> frames, List<ShaderRenderer.Error> errors, String failure,
        List<String> unset) {
      this(frames, errors, failure, unset, List.of(), Map.of(), null);
    }

    /** It uses time and nobody fixed it: it runs live. */
    boolean animated() {
      return ok() && types.containsKey("time") && request != null
          && !request.uniforms().containsKey("time");
    }

    boolean ok() {
      return !frames.isEmpty();
    }
  }

  /** Uniforms the preview fills itself (Processing's and the animation's). */
  private static final java.util.Set<String> BUILT_IN = java.util.Set.of("transformMatrix",
      "texMatrix", "modelviewMatrix", "projectionMatrix", "texture", "texOffset", "time",
      "resolution");

  private ShaderPreviews() {}

  // --- rendering -----------------------------------------------------------------

  static CompletableFuture<Rendered> render(Path root, Path frag, Path vert, Path image,
      Map<String, double[]> uniforms) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        int[] size = size(image);
        var request = new ShaderRenderer.Request(frag, vert, image, size[0], size[1], FRAMES,
            SECONDS, uniforms);
        var result = renderer(root).render(request);
        List<Image> frames = new ArrayList<>();
        for (Path p : result.frames()) frames.add(new Image(p.toUri().toString()));
        List<String> unset = result.uniforms().stream()
            .filter(u -> !BUILT_IN.contains(u) && !uniforms.containsKey(u)).toList();
        return new Rendered(frames, result.errors(), result.failure(), unset,
            result.warnings(), result.uniformTypes(), request);
      } catch (IOException e) {
        return new Rendered(List.of(), List.of(), e.getMessage(), List.of());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Rendered(List.of(), List.of(), "interrupted", List.of());
      }
    }, POOL);
  }

  static ShaderRenderer renderer(Path root) throws IOException {
    return new ShaderRenderer(Path.of(System.getProperty("java.home"), "bin", "java"),
        ScratchProject.open(root).libs(), StudioApp.userLibraryCache().resolve("shader-previews"));
  }

  /**
   * A shader that uses time, running: the next frame is asked for as soon as
   * one is shown (at most 25 a second), with the seconds since the IDE
   * started, so time goes on like in the game instead of looping.
   */
  static final class LiveLoop {
    private static final long CLOCK = System.nanoTime();
    private final Path root;
    private final ShaderRenderer.Request request;
    private final ImageView view;
    private final Path file;
    private ShaderRenderer renderer;
    private boolean running;
    private int run;
    private int shown;

    LiveLoop(Path root, ShaderRenderer.Request request, ImageView view) {
      this.root = root;
      this.request = request;
      this.view = view;
      this.file = StudioApp.userLibraryCache().resolve("shader-previews").resolve("live")
          .resolve(java.util.UUID.randomUUID() + ".png");
    }

    void start() {
      if (running) return;
      running = true;
      tick(++run);
    }

    void stop() {
      running = false;
      run++;
      try {
        Files.deleteIfExists(file);
      } catch (IOException ignored) {
        // a cache file
      }
    }

    boolean running() {
      return running;
    }

    /** Frames shown so far (tests). */
    int shown() {
      return shown;
    }

    private void tick(int mine) {
      long began = System.nanoTime();
      double time = (began - CLOCK) / 1e9;
      CompletableFuture.supplyAsync(() -> {
        try {
          if (renderer == null) renderer = renderer(root);
          if (!renderer.live(request, time, file).ok()) return null;
          // The path is reused for every frame. Load the bytes now so JavaFX
          // cannot reuse a cached image for the same URL.
          try (var input = Files.newInputStream(file)) {
            return new Image(input);
          }
        } catch (IOException e) {
          return null;
        }
      }, POOL).thenAccept(frame -> Platform.runLater(() -> {
        if (!running || mine != run) return;
        if (frame == null || frame.isError()) {
          running = false;
          return;
        }
        view.setImage(frame);
        shown++;
        long spent = (System.nanoTime() - began) / 1_000_000;
        var wait = new javafx.animation.PauseTransition(Duration.millis(Math.max(1, 40 - spent)));
        wait.setOnFinished(e -> {
          if (running && mine == run) tick(mine);
        });
        wait.play();
      }));
    }
  }

  /** The preview size for an image: its shape, the larger side {@link #SIZE}. */
  private static int[] size(Path image) {
    if (image == null) return new int[] {SIZE, SIZE * 3 / 4};
    Image probe = new Image(image.toUri().toString());
    double w = Math.max(1, probe.getWidth());
    double h = Math.max(1, probe.getHeight());
    double scale = SIZE / Math.max(w, h);
    return new int[] {(int) Math.max(8, Math.round(w * scale)),
        (int) Math.max(8, Math.round(h * scale))};
  }

  /**
   * The image a class shows the shader on: a sprite's first costume, a
   * stage's first backdrop (null: none found, a test image is used).
   */
  static Path baseImage(Path root, Path classFile, String source) {
    if (root == null) return null;
    String look;
    if (VisualMode.isStageSource(classFile)) {
      Matcher m = BACKDROP.matcher(source);
      look = m.find() ? m.group(1) : null;
    } else {
      String className = VisualMode.className(classFile);
      look = className == null ? null : SpriteLook.of(root, className);
    }
    if (look == null) return null;
    String path = look;
    int[] crop = null;
    int hash = look.indexOf('#');
    if (hash >= 0) {
      path = look.substring(0, hash);
      try {
        String[] p = look.substring(hash + 1).split(",");
        crop = new int[] {Integer.parseInt(p[0].strip()), Integer.parseInt(p[1].strip()),
            Integer.parseInt(p[2].strip()), Integer.parseInt(p[3].strip())};
      } catch (RuntimeException e) {
        crop = null;
      }
    }
    Path file = root.resolve(path);
    if (!Files.isRegularFile(file)) return null;
    if (crop == null) return file;
    // a cell of a sprite sheet: cut it out once
    try {
      Path cell = StudioApp.userLibraryCache().resolve("shader-previews").resolve("cells")
          .resolve(Integer.toHexString((file + look).hashCode()) + "-"
              + Files.getLastModifiedTime(file).toMillis() + ".png");
      if (!Files.isRegularFile(cell)) {
        var sheet = javax.imageio.ImageIO.read(file.toFile());
        int x = Math.min(crop[0], sheet.getWidth() - 1);
        int y = Math.min(crop[1], sheet.getHeight() - 1);
        var sub = sheet.getSubimage(x, y, Math.min(crop[2], sheet.getWidth() - x),
            Math.min(crop[3], sheet.getHeight() - y));
        Files.createDirectories(cell.getParent());
        javax.imageio.ImageIO.write(sub, "png", cell.toFile());
      }
      return cell;
    } catch (IOException | RuntimeException e) {
      return file;
    }
  }

  // --- the gutter --------------------------------------------------------------

  /**
   * A thumbnail for a {@code getShaders().add(...)} line (null: not such a
   * line or the shader file is missing); hover shows it large and animated, a
   * click opens the shader file.
   */
  static Node forLine(String line, int lineIndex, Path root, Path classFile,
      java.util.function.Supplier<String> sourceText, Consumer<Path> open) {
    if (root == null || classFile == null || line.strip().startsWith("//")
        || !ADD.matcher(line).find()) {
      return null;
    }
    String source = sourceText.get();
    ShaderUses.Use use = ShaderUses.in(source).stream()
        .filter(u -> u.line() == lineIndex).findFirst().orElse(null);
    if (use == null) return null;
    Path frag = root.resolve(use.frag());
    if (!Files.isRegularFile(frag)) return null;
    Path vert = use.vert() == null ? null : root.resolve(use.vert());
    if (vert != null && !Files.isRegularFile(vert)) return null;
    Path image = baseImage(root, classFile, source);

    Label holder = new Label(null, Icons.of("fth-aperture"));
    holder.getStyleClass().addAll("code-preview", "shader-preview");
    holder.setMinWidth(18);
    holder.setCursor(javafx.scene.Cursor.HAND);
    holder.setOnMouseClicked(e -> {
      open.accept(frag);
      e.consume();
    });
    String key;
    try {
      key = root + "|" + frag + "@" + Files.getLastModifiedTime(frag).toMillis() + "|"
          + (vert == null ? "-" : vert + "@" + Files.getLastModifiedTime(vert).toMillis()) + "|"
          + (image == null ? "-" : image + "@" + Files.getLastModifiedTime(image).toMillis())
          + "|" + uniformsText(use.uniforms());
    } catch (IOException e) {
      return null;
    }
    GUTTER.computeIfAbsent(key, k -> render(root, frag, vert, image, use.uniforms()))
        .thenAccept(r -> Platform.runLater(() -> show(holder, use, r, root)));
    return holder;
  }

  private static void show(Label holder, ShaderUses.Use use, Rendered r, Path root) {
    if (!r.ok()) {
      var icon = Icons.of("fth-alert-triangle");
      icon.getStyleClass().add("shader-error-icon");
      holder.setGraphic(icon);
      holder.setTooltip(new Tooltip(use.name() + ": " + errorsText(r)));
      return;
    }
    ImageView thumb = new ImageView(r.frames().get(0));
    thumb.setFitWidth(16);
    thumb.setFitHeight(16);
    thumb.setPreserveRatio(true);
    thumb.setSmooth(true);
    holder.setGraphic(thumb);
    ImageView large = new ImageView(r.frames().get(0));
    LiveLoop live = r.animated() ? new LiveLoop(root, r.request(), large) : null;
    VBox content = new VBox(4, large, new Label(caption(use)));
    if (!r.unset().isEmpty()) {
      Label unset = new Label(I18n.t("shader.unset", String.join(", ", r.unset())));
      unset.setWrapText(true);
      unset.setMaxWidth(SIZE);
      content.getChildren().add(unset);
    }
    content.setAlignment(Pos.CENTER);
    Tooltip tip = new Tooltip();
    tip.setGraphic(content);
    tip.setShowDelay(Duration.millis(250));
    if (live != null) {
      tip.setOnShown(e -> live.start());
      tip.setOnHidden(e -> live.stop());
    }
    holder.setTooltip(tip);
  }

  private static String caption(ShaderUses.Use use) {
    String uniforms = uniformsText(use.uniforms());
    return use.name() + (uniforms.isEmpty() ? "" : "  ·  " + uniforms);
  }

  /** Uniforms a student can give values in the panel: numbers, vectors and booleans. */
  static boolean editable(String name, String type) {
    if (BUILT_IN.contains(name) && !name.equals("time") && !name.equals("resolution")) {
      return false;
    }
    return java.util.Set.of("float", "int", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3",
        "ivec4").contains(type);
  }

  /** How many numbers a GLSL type holds. */
  static int components(String type) {
    return switch (type) {
      case "vec2", "ivec2" -> 2;
      case "vec3", "ivec3" -> 3;
      case "vec4", "ivec4" -> 4;
      default -> 1;
    };
  }

  static String uniformsText(Map<String, double[]> uniforms) {
    StringBuilder sb = new StringBuilder();
    for (var u : uniforms.entrySet()) {
      if (!sb.isEmpty()) sb.append(", ");
      sb.append(u.getKey()).append(" = ");
      double[] v = u.getValue();
      for (int i = 0; i < v.length; i++) {
        if (i > 0) sb.append(' ');
        sb.append(v[i] == Math.rint(v[i]) ? String.valueOf((long) v[i]) : String.valueOf(v[i]));
      }
    }
    return sb.toString();
  }

  static String errorsText(Rendered r) {
    if (!r.errors().isEmpty()) {
      StringBuilder sb = new StringBuilder();
      for (var e : r.errors()) {
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(e.kind()).append(e.line() > 0 ? " " + I18n.t("shader.line", e.line()) : "")
            .append(": ").append(e.message());
      }
      return sb.toString();
    }
    return I18n.t("shader.failed", r.failure() == null ? "" : r.failure());
  }

  // --- beside a shader file ----------------------------------------------------

  /**
   * The panel beside an open {@code .frag}/{@code .vert}: the shader on a
   * class that uses it (or a test image), redrawn while typing; compile errors
   * are marked in the editor.
   */
  static final class Panel extends VBox {

    /** Where the shader is shown: a class's use of it, or the test image (use null). */
    record Target(String label, Path classFile, ShaderUses.Use use) {
      @Override
      public String toString() {
        return label;
      }
    }

    private final CodeEditor editor;
    private final Path file;
    private final Path root;
    private final ComboBox<Target> target = new ComboBox<>();
    private final ImageView view = new ImageView();
    private final Label status = new Label();
    private final Label uniforms = new Label();
    private final javafx.animation.PauseTransition debounce =
        new javafx.animation.PauseTransition(Duration.millis(600));
    private LiveLoop live;
    private int generation;
    /** Values typed in the panel; they win over the code's for the preview only. */
    private final Map<String, double[]> overrides = new java.util.LinkedHashMap<>();
    private boolean animateTime = true;
    private double[] lastSize = {SIZE, SIZE};
    private double fixedTime;
    private final VBox inputs = new VBox(6);
    private String inputsShape = "";
    private Map<String, double[]> codeValues = Map.of();
    private final java.util.Map<String, javafx.scene.control.Spinner<Double>> fields =
        new java.util.LinkedHashMap<>();

    Panel(CodeEditor editor, Path file, Path root) {
      this.editor = editor;
      this.file = file;
      this.root = root;
      getStyleClass().add("shader-panel");
      setSpacing(8);
      setPadding(new Insets(10));
      Label title = new Label(I18n.t("shader.preview"));
      title.getStyleClass().add("title-4");
      target.setMaxWidth(Double.MAX_VALUE);
      target.getItems().setAll(targets());
      target.getSelectionModel().select(0);
      target.valueProperty().addListener((o, a, b) -> {
        overrides.clear();
        inputsShape = "";
        refresh();
      });
      StackPane stage = new StackPane(view);
      stage.getStyleClass().add("shader-stage");
      stage.setMinHeight(SIZE + 20);
      view.setPreserveRatio(true);
      status.setWrapText(true);
      status.getStyleClass().add("shader-status");
      uniforms.setWrapText(true);
      uniforms.getStyleClass().add("text-muted");
      Label hint = new Label(I18n.t("shader.hint"));
      hint.setWrapText(true);
      hint.getStyleClass().add("text-muted");
      inputs.getStyleClass().add("shader-inputs");
      getChildren().addAll(title, new Label(I18n.t("shader.on")), target, stage, status,
          inputs, uniforms, hint);
      for (Label l : new Label[] {status, uniforms, hint}) {
        l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
      }
      debounce.setOnFinished(e -> refresh());
      // not shown any more (tab closed): no frames for nobody
      sceneProperty().addListener((o, a, b) -> {
        if (b == null && live != null) live.stop();
      });
      editor.area().textProperty().addListener((o, a, b) -> debounce.playFromStart());
      refresh();
    }

    /** The classes whose code adds this shader file, then the test image. */
    private List<Target> targets() {
      List<Target> out = new ArrayList<>();
      String relative = root.relativize(file).toString().replace('\\', '/');
      try (Stream<Path> files = Files.list(root)) {
        for (Path java : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
          String source = Files.readString(java, StandardCharsets.UTF_8);
          for (var use : ShaderUses.of(source, relative)) {
            out.add(new Target(VisualMode.className(java) + " · " + use.name(), java, use));
          }
        }
      } catch (IOException ignored) {
        // the test image stays
      }
      out.add(new Target(I18n.t("shader.testimage"), null, null));
      return out;
    }

    Target target() {
      return target.getValue();
    }

    ComboBox<Target> targetChoice() {
      return target;
    }

    String statusText() {
      return status.getText();
    }

    Image image() {
      return view.getImage();
    }

    /** Renders the editor's current text (also unsaved) on the chosen target. */
    void refresh() {
      Target t = target.getValue();
      int run = ++generation;
      status.setText(I18n.t("shader.rendering"));
      boolean isVert = file.getFileName().toString().endsWith(".vert");
      try {
        // the text as typed, saved beside the cache (the file itself may be unsaved)
        Path draft = StudioApp.userLibraryCache().resolve("shader-previews").resolve("drafts")
            .resolve(Integer.toHexString(file.toString().hashCode()))
            .resolve(file.getFileName().toString());
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, editor.content(), StandardCharsets.UTF_8);
        Path frag;
        Path vert;
        Path image = null;
        Map<String, double[]> values = Map.of();
        if (t != null && t.use() != null) {
          String source = Files.readString(t.classFile(), StandardCharsets.UTF_8);
          image = baseImage(root, t.classFile(), source);
          values = t.use().uniforms();
          frag = isVert ? root.resolve(t.use().frag()) : draft;
          vert = isVert ? draft : t.use().vert() == null ? null : root.resolve(t.use().vert());
        } else {
          if (isVert) {
            status.setText(I18n.t("shader.vertonly"));
            return;
          }
          frag = draft;
          vert = null;
        }
        uniforms.setText(values.isEmpty() ? "" : I18n.t("shader.uniforms",
            uniformsText(values)));
        codeValues = values;
        Map<String, double[]> used = new java.util.LinkedHashMap<>(values);
        used.putAll(overrides);
        if (!animateTime) {
          used.put("time", new double[] {fixedTime});
        }
        render(root, frag, vert, image, used).thenAccept(r -> Platform.runLater(() -> {
          if (run != generation) return;
          show(r, isVert ? "vert" : "frag");
        }));
      } catch (IOException e) {
        status.setText(e.getMessage());
      }
    }

    /** Values a field shows: typed, from the code, the preview size, or 0. */
    private double[] valueOf(String name, String type) {
      double[] v = overrides.get(name);
      if (v == null) v = codeValues.get(name);
      if (v == null && name.equals("resolution")) {
        v = lastSize; // what the preview sets when nobody does
      }
      double[] out = new double[components(type)];
      if (v != null) System.arraycopy(v, 0, out, 0, Math.min(v.length, out.length));
      return out;
    }

    /** One row per uniform; rebuilt only when the shader's uniforms change. */
    private void buildInputs(Map<String, String> types) {
      StringBuilder shape = new StringBuilder();
      types.forEach((n, t) -> {
        if (editable(n, t)) shape.append(n).append(':').append(t).append(' ');
      });
      if (shape.toString().equals(inputsShape)) return;
      inputsShape = shape.toString();
      inputs.getChildren().clear();
      fields.clear();
      if (inputsShape.isEmpty()) return;
      Label header = new Label(I18n.t("shader.values"));
      header.getStyleClass().add("text-bold");
      javafx.scene.control.Button reset = new javafx.scene.control.Button(
          I18n.t("shader.values.reset"));
      reset.getStyleClass().addAll("flat", "small");
      reset.setOnAction(e -> {
        overrides.clear();
        animateTime = true;
        inputsShape = "";
        refresh();
      });
      javafx.scene.layout.HBox top = new javafx.scene.layout.HBox(8, header,
          new javafx.scene.layout.Region(), reset);
      javafx.scene.layout.HBox.setHgrow(top.getChildren().get(1),
          javafx.scene.layout.Priority.ALWAYS);
      top.setAlignment(Pos.CENTER_LEFT);
      inputs.getChildren().add(top);
      javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
      grid.setHgap(6);
      grid.setVgap(4);
      int row = 0;
      for (var entry : types.entrySet()) {
        String name = entry.getKey();
        String type = entry.getValue();
        if (!editable(name, type)) continue;
        Label label = new Label(name);
        label.setTooltip(new Tooltip(type + " " + name));
        grid.add(label, 0, row);
        javafx.scene.layout.HBox values = new javafx.scene.layout.HBox(4);
        if (type.equals("bool")) {
          javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox();
          box.setSelected(valueOf(name, type)[0] != 0);
          box.selectedProperty().addListener((o, a, b) -> {
            overrides.put(name, new double[] {b ? 1 : 0});
            debounce.playFromStart();
          });
          values.getChildren().add(box);
        } else {
          javafx.scene.control.CheckBox animate = null;
          if (name.equals("time")) {
            animate = new javafx.scene.control.CheckBox(I18n.t("shader.time.animate"));
            animate.setSelected(animateTime);
          }
          double[] start = name.equals("time") ? new double[] {fixedTime}
              : valueOf(name, type);
          boolean integer = type.startsWith("i") || type.equals("int");
          List<javafx.scene.control.Spinner<Double>> spinners = new ArrayList<>();
          for (int i = 0; i < start.length; i++) {
            javafx.scene.control.Spinner<Double> spinner = new javafx.scene.control.Spinner<>(
                new javafx.scene.control.SpinnerValueFactory.DoubleSpinnerValueFactory(
                    -1_000_000, 1_000_000, start[i], integer ? 1 : 0.1));
            spinner.setEditable(true);
            spinner.setPrefWidth(start.length > 2 ? 78 : 96);
            spinner.getStyleClass().add("small");
            // typed numbers count without Enter
            spinner.getEditor().textProperty().addListener((o, a, b) -> {
              try {
                double v = Double.parseDouble(b.replace(',', '.'));
                if (v != spinner.getValue()) spinner.getValueFactory().setValue(v);
              } catch (NumberFormatException ignored) {
                // still typing
              }
            });
            spinners.add(spinner);
            values.getChildren().add(spinner);
            fields.put(start.length == 1 ? name : name + "[" + i + "]", spinner);
          }
          javafx.scene.control.CheckBox animateBox = animate;
          Runnable changed = () -> {
            double[] v = new double[spinners.size()];
            for (int i = 0; i < v.length; i++) {
              v[i] = integer ? Math.round(spinners.get(i).getValue())
                  : spinners.get(i).getValue();
            }
            if (name.equals("time")) {
              fixedTime = v[0];
              if (animateBox.isSelected()) return;
            } else {
              overrides.put(name, v);
            }
            debounce.playFromStart();
          };
          for (var spinner : spinners) {
            spinner.valueProperty().addListener((o, a, b) -> changed.run());
          }
          if (animate != null) {
            spinners.get(0).setDisable(animateTime);
            animate.selectedProperty().addListener((o, a, b) -> {
              animateTime = b;
              spinners.get(0).setDisable(b);
              debounce.playFromStart();
            });
            values.getChildren().add(animate);
          }
        }
        values.setAlignment(Pos.CENTER_LEFT);
        grid.add(values, 1, row);
        row++;
      }
      inputs.getChildren().add(grid);
    }

    /** A value field (tests): the uniform's name, or name[i] for a vector's part. */
    javafx.scene.control.Spinner<Double> field(String name) {
      return fields.get(name);
    }

    /** The running preview (tests). */
    LiveLoop live() {
      return live;
    }

    private void show(Rendered r, String kind) {
      if (live != null) live.stop();
      live = null;
      List<CodeEditor.Diagnostic> marks = new ArrayList<>();
      for (var e : r.errors()) {
        if (e.kind().equals(kind) && e.line() > 0) {
          marks.add(new CodeEditor.Diagnostic(e.line(), 0, e.message(), "", true));
        }
      }
      for (var w : r.warnings()) {
        if (w.kind().equals(kind) && w.line() > 0) {
          marks.add(new CodeEditor.Diagnostic(w.line(), 0, w.message(), "", false));
        }
      }
      editor.setDiagnostics(marks);
      if (r.ok()) {
        lastSize = new double[] {r.frames().get(0).getWidth(), r.frames().get(0).getHeight()};
      }
      if (!r.types().isEmpty()) {
        buildInputs(r.types());
      }
      if (!r.ok()) {
        status.setText(errorsText(r));
        status.getStyleClass().add("danger");
        view.setImage(null);
        return;
      }
      status.getStyleClass().remove("danger");
      StringBuilder warnings = new StringBuilder();
      for (var w : r.warnings()) {
        warnings.append('\n').append(I18n.t("shader.warning",
            w.line() > 0 ? I18n.t("shader.line", w.line()) : w.kind(), w.message()));
      }
      status.setText((r.animated() ? I18n.t("shader.animated") : I18n.t("shader.ok"))
          + (r.unset().isEmpty() ? "" : "\n" + I18n.t("shader.unset",
              String.join(", ", r.unset()))) + warnings);
      view.setImage(r.frames().get(0));
      if (r.animated()) {
        live = new LiveLoop(root, r.request(), view);
        live.start();
      }
    }
  }
}
