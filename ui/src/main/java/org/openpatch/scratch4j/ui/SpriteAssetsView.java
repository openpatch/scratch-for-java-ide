package org.openpatch.scratch4j.ui;

import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.SpriteAssets;
import org.openpatch.scratch4j.core.region.HitboxPolygon;
import org.openpatch.scratch4j.core.region.SpriteRegionWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;

/** Sprite-class costumes, sounds, animations and a polygon hitbox editor. */
final class SpriteAssetsView extends BorderPane {

  private final ScratchProject project;
  private final Path file;
  private final Runnable onSaved;
  /** After a hitbox save (often, while shaping it): only what has to follow at once. */
  private Runnable onHitboxSaved;
  private Tab costumesTab;
  private Tab soundsTab;
  /** Null for a class that is no AnimatedSprite. */
  private Tab animationsTab;
  private final ListView<SpriteAssets.Entry> costumes = new ListView<>();
  private final ListView<SpriteAssets.Entry> sounds = new ListView<>();
  private final ListView<SpriteAssets.Entry> animations = new ListView<>();
  private final Canvas hitboxCanvas = new Canvas(520, 380);
  private final ComboBox<String> hitboxCostume = new ComboBox<>();
  /** Costumes the constructor adds from its parameters, per way the project makes it. */
  private final VBox fromCode = new VBox(6);
  private final List<Point2D> vertices = new ArrayList<>();
  private final Label hitboxHelp = new Label();
  private final Label hitboxState = new Label();
  /** A valid hitbox saves itself this long after its last change (a drag is many). */
  private final javafx.animation.PauseTransition hitboxSave =
      new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
  /** The selected point's X/Y and its Remove button: only while a point is selected. */
  private HBox pointRow;
  private Button removePointButton;
  private Label pointsLabel;
  private final ListView<String> pointList = new ListView<>();
  private final TextField pointX = new TextField();
  private final TextField pointY = new TextField();
  private final Deque<List<Point2D>> hitboxUndo = new ArrayDeque<>();
  private final Deque<List<Point2D>> hitboxRedo = new ArrayDeque<>();
  private Image hitboxImage;
  private double imageScale = 1;
  private double imageX;
  private double imageY;
  private int dragging = -1;
  private int selectedPoint = -1;
  private boolean dragRecorded;
  private boolean hitboxDirty;
  private boolean updatingPointFields;
  private double[] loadedHitbox = new double[0];
  private boolean hitboxReadOnly;
  /** (sprite file, animation name, project-relative frame pattern) */
  private AnimationOpener onOpenAnimation = (file, name, pattern) -> { };

  interface AnimationOpener {
    void open(Path spriteFile, String name, String pattern);
  }

  SpriteAssetsView(ScratchProject project, Path sourceFile, Runnable onSaved)
      throws IOException {
    this.project = project;
    this.file = sourceFile;
    this.onSaved = onSaved;
    TabPane tabs = new TabPane();
    costumesTab = new Tab(I18n.t("spriteassets.costumes"), assetPane(costumes,
        SpriteAssets.Kind.COSTUME));
    soundsTab = new Tab(I18n.t("spriteassets.sounds"), assetPane(sounds,
        SpriteAssets.Kind.SOUND));
    tabs.getTabs().addAll(costumesTab, soundsTab);
    // every sprite has animations in 7.0
    if (VisualMode.isAnimatedSprite(sourceFile)) {
      animationsTab = new Tab(I18n.t("spriteassets.animations"), assetPane(animations,
          SpriteAssets.Kind.ANIMATION));
      tabs.getTabs().add(animationsTab);
    }
    tabs.getTabs().add(new Tab(I18n.t("spriteassets.hitbox"), hitboxPane()));
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    tabs.getStyleClass().add("sprite-tabs");
    getStyleClass().add("sprite-editor");
    setCenter(tabs);
    hitboxCostume.setConverter(new javafx.util.StringConverter<>() {
      @Override public String toString(String ref) {
        return ref == null ? "" : hitboxLabels.getOrDefault(ref, ref);
      }
      @Override public String fromString(String text) {
        return text;
      }
    });
    reload();
    // the first entry shows in the preview, not "choose an entry"
    for (ListView<SpriteAssets.Entry> list : List.of(costumes, sounds, animations)) {
      if (list.getSelectionModel().isEmpty() && !list.getItems().isEmpty()) {
        list.getSelectionModel().selectFirst();
      }
    }
  }

  SpriteAssetsView(ScratchProject project, String className, Runnable onSaved)
      throws IOException {
    this(project, project.root().resolve(className + ".java"), onSaved);
  }

  Path file() { return file; }

  void setOnOpenAnimation(AnimationOpener opener) {
    this.onOpenAnimation = opener;
  }

  /**
   * The animation editor works on frame files in the project: an existing
   * file-pattern animation opens as it is, otherwise a new one is named and
   * gets frames {@code assets/images/<name><n>.png}.
   */
  private void openAnimationEditor(SpriteAssets.Entry selected) {
    if (selected != null && selected.kind() == SpriteAssets.Kind.ANIMATION
        && !selected.isSheetAnimation() && selected.reference() != null
        && selected.reference().matches(".*%d.*\\.[A-Za-z]+$")) {
      onOpenAnimation.open(file, selected.name(), selected.reference());
      return;
    }
    javafx.scene.control.TextInputDialog dialog =
        new javafx.scene.control.TextInputDialog("walk");
    dialog.setTitle(I18n.t("animation.new"));
    dialog.setHeaderText(I18n.t("animation.new.hint"));
    dialog.setContentText(I18n.t("spriteassets.name"));
    Theme.style(dialog);
    dialog.showAndWait().map(String::trim).filter(n -> n.matches("[A-Za-z][A-Za-z0-9_]*"))
        .ifPresent(n -> onOpenAnimation.open(file, n, "assets/images/" + n + "%d.png"));
  }

  /**
   * Before the editor closes: a valid hitbox not saved yet is saved now; an
   * unfinished one (too few points, crossed edges) is let go, the code keeps
   * the last valid one. Nothing to ask, so closing always goes on.
   */
  boolean confirmClose() {
    saveHitboxNow();
    return true;
  }

  private void hitboxChanged() {
    hitboxDirty = true;
    hitboxSave.playFromStart();
  }

  /** Saves a changed, valid hitbox into the code (also right away, for closing and tests). */
  void saveHitboxNow() {
    hitboxSave.stop();
    if (!hitboxDirty || hitboxReadOnly || hitboxImage == null) return;
    if (dragging >= 0) {
      hitboxSave.playFromStart(); // once the point is let go
      return;
    }
    if (!HitboxPolygon.valid(polygon(), hitboxImage.getWidth(), hitboxImage.getHeight())) {
      refreshHitboxState();
      return;
    }
    double[] points = shownHitbox();
    // only the hitbox changed: no reload of the lists and previews (no hitch after a drag)
    try {
      String source = currentSource();
      if (!java.util.Arrays.equals(SpriteAssets.hitbox(source), loadedHitbox)) {
        throw new IllegalStateException(I18n.t("spriteassets.hitbox.conflict"));
      }
      String updated = SpriteRegionWriter.setHitbox(source, points);
      if (!updated.equals(source)) {
        LocalHistory.writeString(project.root(), file, updated);
      }
      loadedHitbox = points;
      hitboxDirty = false;
      refreshHitboxState();
      if (!updated.equals(source)) (onHitboxSaved != null ? onHitboxSaved : onSaved).run();
    } catch (IOException | RuntimeException e) {
      alert(e.getMessage(), e);
      reload();
    }
  }

  /** What follows a hitbox save; without one, the same as after any other save. */
  void setOnHitboxSaved(Runnable action) {
    this.onHitboxSaved = action;
  }

  /** Saved, about to be saved, or not saved because the shape is not finished. */
  private void refreshHitboxState() {
    boolean valid = hitboxImage != null
        && HitboxPolygon.valid(polygon(), hitboxImage.getWidth(), hitboxImage.getHeight());
    hitboxState.setText(I18n.t(!hitboxDirty ? "spriteassets.hitbox.saved"
        : valid ? "spriteassets.hitbox.saving" : "spriteassets.hitbox.pending"));
  }

  void reload() {
    try {
      String source = currentSource();
      List<SpriteAssets.Entry> entries = SpriteAssets.list(source);
      costumes.getItems().setAll(entries.stream()
          .filter(e -> e.kind() == SpriteAssets.Kind.COSTUME
              || e.kind() == SpriteAssets.Kind.SHEET).toList());
      sounds.getItems().setAll(entries.stream()
          .filter(e -> e.kind() == SpriteAssets.Kind.SOUND).toList());
      animations.getItems().setAll(entries.stream()
          .filter(e -> e.kind() == SpriteAssets.Kind.ANIMATION).toList());
      // equal entries keep their cells: redraw them, a picture may have been repainted
      costumes.refresh();
      animations.refresh();
      for (var kind : List.of(SpriteAssets.Kind.COSTUME, SpriteAssets.Kind.ANIMATION)) {
        AssetPreview preview = previews.get(kind);
        if (preview != null) {
          preview.show(list(kind).getSelectionModel().getSelectedItem());
        }
      }
      hitboxReadOnly = SpriteAssets.hasUnmanagedHitbox(source);
      if (!hitboxDirty) {
        try {
          double[] points = SpriteAssets.hitbox(source);
          loadedHitbox = points.clone();
          // just saved from here: the selection and the undo steps stay
          if (!java.util.Arrays.equals(points, shownHitbox())) {
            vertices.clear();
            selectedPoint = -1;
            hitboxUndo.clear();
            hitboxRedo.clear();
            for (int i = 0; i < points.length; i += 2) {
              vertices.add(new Point2D(points[i], points[i + 1]));
            }
          }
        } catch (IllegalArgumentException e) {
          vertices.clear();
          selectedPoint = -1;
          hitboxUndo.clear();
          hitboxRedo.clear();
          hitboxReadOnly = true;
        }
      }
      hitboxHelp.setText(I18n.t(hitboxReadOnly
          ? "spriteassets.hitbox.handwritten" : "spriteassets.hitbox.help"));
      String previous = hitboxCostume.getValue();
      // what the sprite looks like: its costumes and each animation's first frame (a cell of
      // a sheet, not the whole sheet), the one it starts with first
      List<String> images = new ArrayList<>();
      hitboxLabels.clear();
      for (SpriteAssets.Entry entry : entries) {
        if (entry.kind() == SpriteAssets.Kind.ANIMATION) {
          List<String> frames = AssetPreview.frameRefs(entry);
          for (int i = 0; i < frames.size(); i++) {
            if (!images.contains(frames.get(i))) {
              images.add(frames.get(i));
              hitboxLabels.put(frames.get(i), I18n.t("spriteassets.hitbox.frame", entry.name(),
                  i + 1, frames.size()));
            }
          }
          continue;
        }
        String ref = switch (entry.kind()) {
          case COSTUME -> AssetPreview.costumeRef(entry);
          case SHEET -> entry.isComputed() ? null : entry.reference() + "#0,0,"
              + entry.tileWidth() + "," + entry.tileHeight();
          default -> null;
        };
        if (ref != null && !images.contains(ref)) {
          images.add(ref);
          hitboxLabels.put(ref, entry.name());
        }
      }
      String className = file.getFileName().toString().replaceFirst("\\.java$", "");
      showFromCode(className, entries, images);
      String start = org.openpatch.scratch4j.core.region.SpriteLook.of(project.root(),
          className);
      if (start != null) {
        images.remove(start);
        images.add(0, start);
        hitboxLabels.putIfAbsent(start, start);
      }
      hitboxCostume.getItems().setAll(images);
      hitboxCostume.setValue(images.contains(previous) ? previous
          : images.isEmpty() ? null : images.get(0));
      hitboxImage = CostumeView.costume(hitboxCostume.getValue(), project.root());
      drawHitbox();
      refreshPoints();
    } catch (IOException | RuntimeException e) {
      alert(e.getMessage(), e);
    }
  }

  /**
   * Costumes the code works out rather than names ({@code addCostume(creature)}):
   * shown read-only below the list, one row per {@code new Racer("bee", ...)}
   * in the project, and offered as hitbox images.
   */
  private void showFromCode(String className, List<SpriteAssets.Entry> entries,
      List<String> images) {
    fromCode.getChildren().clear();
    java.util.Set<String> named = new java.util.HashSet<>();
    for (SpriteAssets.Entry entry : entries) {
      named.add(entry.name());
      if (entry.reference() != null) named.add(entry.reference());
    }
    List<org.openpatch.scratch4j.core.region.SpriteLook.Variant> variants =
        org.openpatch.scratch4j.core.region.SpriteLook.variants(project.root(), className);
    boolean any = false;
    for (var variant : variants) {
      List<String> extra = variant.costumes().stream()
          .filter(c -> !named.contains(c) && !c.contains("#")).toList();
      if (extra.isEmpty()) continue;
      any = true;
      javafx.scene.layout.FlowPane row = new javafx.scene.layout.FlowPane(10, 6);
      for (String costume : extra) {
        Image image = CostumeView.costume(costume, project.root());
        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
        view.setFitWidth(40);
        view.setFitHeight(40);
        view.setPreserveRatio(true);
        Label tile = new Label(costume, view);
        tile.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        tile.getStyleClass().add("code-costume");
        row.getChildren().add(tile);
        if (!images.contains(costume)) {
          images.add(costume);
          hitboxLabels.put(costume, costume);
        }
      }
      Label made = new Label(variant.creation().isEmpty()
          ? I18n.t("spriteassets.fromcode.any") : variant.creation());
      made.getStyleClass().add("code-costume-creation");
      fromCode.getChildren().addAll(made, row);
    }
    if (any) {
      Label title = new Label(I18n.t("spriteassets.fromcode"));
      title.getStyleClass().add("form-label");
      Label hint = new Label(I18n.t("spriteassets.fromcode.hint"));
      hint.setWrapText(true);
      hint.getStyleClass().add("card-button-hint");
      fromCode.getChildren().addAll(0, List.of(title, hint));
    }
    fromCode.setVisible(any);
    fromCode.setManaged(any);
  }

  private javafx.scene.Node assetPane(ListView<SpriteAssets.Entry> list,
      SpriteAssets.Kind kind) {
    AssetPreview preview = new AssetPreview(project.root());
    previews.put(kind, preview);
    list.getSelectionModel().selectedItemProperty().addListener((o, a, entry) -> {
      preview.show(entry);
      if (entry != null) onEntrySelected.accept(entry);
    });
    list.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
      @Override protected void updateItem(SpriteAssets.Entry entry, boolean empty) {
        super.updateItem(entry, empty);
        setGraphic(empty || entry == null ? null : AssetPreview.thumbnail(entry, project.root()));
        setGraphicTextGap(10);
        setText(empty || entry == null ? null : entry.name()
            + (entry.isComputed() ? "  ·  " + I18n.t("spriteassets.computed", entry.computed())
                : entry.reference() == null ? "" : "  ·  " + entry.reference())
            + (entry.kind() == SpriteAssets.Kind.ANIMATION
                ? "  ·  " + I18n.t("spriteassets.frames", entry.frames()) : "")
            + (entry.tileWidth() > 0 ? "  ·  " + I18n.t("spriteassets.tiles",
                entry.tileWidth(), entry.tileHeight())
                + (entry.columns() ? " · " + I18n.t("spriteassets.column", entry.row())
                    // 7.0 rows are frame numbers (row() is the first frame then): no row to name
                    : entry.sheetFrames().isEmpty() && entry.row() > 0
                        ? " · " + I18n.t("spriteassets.row", entry.row()) : "")
                : "")
            + (entry.managed() ? "" : "  ·  " + I18n.t("spriteassets.handwritten")));
      }
    });
    Button add = new Button(I18n.t("spriteassets.add"), Icons.of("fth-plus"));
    add.setOnAction(e -> add(kind));
    Button remove = new Button(I18n.t("spriteassets.remove"), Icons.of("fth-trash-2"));
    remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
    remove.setOnAction(e -> {
      SpriteAssets.Entry selected = list.getSelectionModel().getSelectedItem();
      if (selected != null) {
        if (selected.isComputed()) {
          inform(I18n.t("spriteassets.computed.warning", selected.computed()));
        } else if (!selected.managed()) {
          alert(I18n.t("spriteassets.handwritten.warning"));
        } else {
          write(source -> SpriteAssets.remove(source, selected));
        }
      }
    });
    // Edit: the paint editor, the sound editor or the animation editor
    Button edit = new Button(I18n.t("spriteassets.edit"), Icons.of("fth-edit-2"));
    edit.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
    edit.setOnAction(e -> edit(list.getSelectionModel().getSelectedItem()));
    list.setOnMouseClicked(e -> {
      if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
        edit(list.getSelectionModel().getSelectedItem());
      }
    });
    // narrow (beside the code): the buttons wrap instead of shortening to "..."
    javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(8, 8, add, edit,
        remove);
    buttons.getStyleClass().add("asset-actions");
    if (kind != SpriteAssets.Kind.SOUND) {
      Button sheet = new Button(I18n.t("spriteassets.fromsheet"), Icons.of("fth-grid"));
      sheet.setOnAction(e -> fromSheet(kind));
      buttons.getChildren().add(sheet);
    }
    if (kind == SpriteAssets.Kind.ANIMATION) {
      Button editor = new Button(I18n.t("animation.new"), Icons.of("fth-film"));
      editor.setOnAction(e -> openAnimationEditor(null));
      buttons.getChildren().add(editor);
    }
    // only when the list has entries from hand-written code (marked "code")
    Label note = new Label(I18n.t("spriteassets.handwritten.note"));
    note.getStyleClass().add("text-muted");
    var handwritten = javafx.beans.binding.Bindings.createBooleanBinding(
        () -> list.getItems().stream().anyMatch(e -> !e.managed()), list.getItems());
    VBox pane = new VBox(8, buttons, list, note);
    if (kind == SpriteAssets.Kind.COSTUME) {
      javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(fromCode);
      scroll.setFitToWidth(true);
      scroll.visibleProperty().bind(fromCode.visibleProperty());
      scroll.managedProperty().bind(fromCode.managedProperty());
      pane.getChildren().add(scroll);
      // a class whose costumes all come from the code: they get the room, not an empty list
      var listShown = javafx.beans.binding.Bindings.isNotEmpty(list.getItems())
          .or(fromCode.visibleProperty().not());
      list.visibleProperty().bind(listShown);
      list.managedProperty().bind(listShown);
      note.visibleProperty().bind(listShown.and(handwritten));
      note.managedProperty().bind(note.visibleProperty());
      java.util.function.Consumer<Boolean> layout = shown -> {
        VBox.setVgrow(scroll, shown ? javafx.scene.layout.Priority.NEVER
            : javafx.scene.layout.Priority.ALWAYS);
        scroll.setMaxHeight(shown ? 220 : Double.MAX_VALUE);
      };
      // also now: a class whose costumes all come from the code never changes it
      layout.accept(listShown.get());
      listShown.addListener((o, was, shown) -> layout.accept(shown));
    }
    if (kind != SpriteAssets.Kind.COSTUME) {
      note.visibleProperty().bind(handwritten);
      note.managedProperty().bind(handwritten);
    }
    VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
    pane.setPadding(new Insets(8));
    // nothing to preview in an empty list
    var any = javafx.beans.binding.Bindings.isNotEmpty(list.getItems());
    preview.visibleProperty().bind(any);
    preview.managedProperty().bind(any);
    // the preview beside the list, or under it when the editor is narrow
    BorderPane layout = new BorderPane(pane);
    layout.widthProperty().addListener((o, old, w) -> {
      boolean narrow = w.doubleValue() < 620;
      if (narrow && layout.getBottom() != preview) {
        layout.setRight(null);
        layout.setBottom(preview);
      } else if (!narrow && layout.getRight() != preview) {
        layout.setBottom(null);
        layout.setRight(preview);
      }
    });
    layout.setRight(preview);
    BorderPane.setMargin(preview, new Insets(8));
    return layout;
  }

  /** Hitbox costume choices: reference -> the asset's name. */
  private final java.util.Map<String, String> hitboxLabels = new java.util.HashMap<>();

  private final java.util.Map<SpriteAssets.Kind, AssetPreview> previews =
      new java.util.EnumMap<>(SpriteAssets.Kind.class);
  private java.util.function.Consumer<Path> onEditFile = f -> { };

  /**
   * The sprite-sheet grid for {@code sheet}: the chosen cells replace
   * {@code replaced} (in place) or are added when it is null.
   */
  private void editSheet(String sheet, SheetGridDialog.Mode mode, SpriteAssets.Entry preset,
      SpriteAssets.Entry replaced) {
    if (replaced != null && !replaced.managed()) {
      alert(I18n.t("spriteassets.handwritten.warning"));
      return;
    }
    Image image = CostumeView.costume(sheet, project.root());
    if (image == null) {
      alert(I18n.t("spriteassets.edit.missing", sheet));
      return;
    }
    SheetGridDialog.show(image, sheet, mode, preset).ifPresent(statements ->
        write(source -> SpriteAssets.replace(source, replaced, statements)));
  }

  /** "From sprite sheet...": a project image cut into costumes or an animation. */
  private void fromSheet(SpriteAssets.Kind kind) {
    // Scratch for Java 7: every sprite can have animations
    if (kind == SpriteAssets.Kind.ANIMATION && !VisualMode.isAnimatedSprite(file)) {
      alert(I18n.t("spriteassets.animated.only"));
      return;
    }
    AssetPickerDialog.pickImage(project.root(), I18n.t("spriteassets.fromsheet"), false)
        .ifPresent(ref -> editSheet(ref, kind == SpriteAssets.Kind.ANIMATION
            ? SheetGridDialog.Mode.ANIMATION : SheetGridDialog.Mode.COSTUMES, null, null));
  }

  /** Opens an image or sound file in its editor (paint / sound editor). */
  void setOnEditFile(java.util.function.Consumer<Path> action) {
    this.onEditFile = action;
  }

  private java.util.function.Consumer<SpriteAssets.Entry> onEntrySelected = e -> { };

  /** Told the entry chosen in a list (the code beside shows its line). */
  void setOnEntrySelected(java.util.function.Consumer<SpriteAssets.Entry> listener) {
    this.onEntrySelected = listener;
  }

  /**
   * The caret is on a line in the code beside: the entry that line adds is
   * chosen in its list (and its tab shown).
   */
  boolean selectFromCodeLine(String line) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
        "\\badd(Costume|Costumes|Animation|Sound)\\(\\s*\"([^\"]*)\"").matcher(line);
    if (!m.find()) return false;
    SpriteAssets.Kind kind = switch (m.group(1)) {
      case "Sound" -> SpriteAssets.Kind.SOUND;
      case "Animation" -> SpriteAssets.Kind.ANIMATION;
      default -> SpriteAssets.Kind.COSTUME;
    };
    ListView<SpriteAssets.Entry> list = list(kind);
    for (SpriteAssets.Entry e : list.getItems()) {
      if (e.name().equals(m.group(2))) {
        if (!e.equals(list.getSelectionModel().getSelectedItem())) {
          list.getSelectionModel().select(e);
        }
        Tab tab = kind == SpriteAssets.Kind.SOUND ? soundsTab
            : kind == SpriteAssets.Kind.ANIMATION ? animationsTab : costumesTab;
        if (tab != null && tab.getTabPane() != null) {
          tab.getTabPane().getSelectionModel().select(tab);
        }
        return true;
      }
    }
    return false;
  }

  /** The hitbox tab's chosen costume (tests). */
  String hitboxCostume() {
    return hitboxCostume.getValue();
  }

  /** The preview of a tab (tests). */
  AssetPreview preview(SpriteAssets.Kind kind) {
    return previews.get(kind);
  }

  ListView<SpriteAssets.Entry> list(SpriteAssets.Kind kind) {
    return switch (kind) {
      case SOUND -> sounds;
      case ANIMATION -> animations;
      default -> costumes;
    };
  }

  /**
   * Edit: a project image or sound opens in its editor, a frame-file animation
   * in the animation editor, a sheet animation's sheet in the paint editor. A
   * built-in image or sound is first copied into the project (with consent)
   * and, when the line is managed, the sprite uses the copy from then on.
   */
  void edit(SpriteAssets.Entry entry) {
    if (entry == null) return;
    if (entry.isComputed()) {
      inform(I18n.t("spriteassets.computed.warning", entry.computed()));
      return;
    }
    if (entry.isSheetAnimation() || entry.isSheetCostume()
        || entry.kind() == SpriteAssets.Kind.SHEET) {
      editSheet(entry.reference(), entry.kind() == SpriteAssets.Kind.ANIMATION
          ? SheetGridDialog.Mode.ANIMATION : SheetGridDialog.Mode.COSTUMES,
          entry.kind() == SpriteAssets.Kind.SHEET ? null : entry, entry);
      return;
    }
    if (entry.kind() == SpriteAssets.Kind.ANIMATION && !entry.isSheetAnimation()) {
      openAnimationEditor(entry);
      return;
    }
    String ref = entry.kind() == SpriteAssets.Kind.SOUND
        ? (entry.reference() != null ? entry.reference() : entry.name())
        : AssetPreview.costumeRef(entry);
    Path inProject = ref == null ? null : project.root().resolve(ref).normalize();
    if (inProject != null && ref.matches("(?i).+\\.[a-z0-9]+$") && Files.isRegularFile(inProject)) {
      onEditFile.accept(inProject);
      return;
    }
    if (entry.kind() != SpriteAssets.Kind.COSTUME && entry.kind() != SpriteAssets.Kind.SOUND) {
      alert(I18n.t("spriteassets.edit.missing", ref));
      return;
    }
    Alert ask = new Alert(Alert.AlertType.CONFIRMATION, I18n.t("spriteassets.edit.builtin",
        entry.name()), I18n.ok(), I18n.cancel());
    ask.setHeaderText(null);
    Theme.style(ask);
    if (ask.showAndWait().orElse(null) != I18n.ok()) return;
    try {
      Path copy = entry.kind() == SpriteAssets.Kind.SOUND
          ? org.openpatch.scratch4j.core.assets.AssetCopier.copyBuiltinSound(entry.name(),
              project.root())
          : org.openpatch.scratch4j.core.assets.AssetCopier.copyBuiltinImage(
              org.openpatch.scratch4j.core.assets.BuiltinAssetIndex.get().image(entry.name())
                  .orElseThrow(() -> new IOException(I18n.t("spriteassets.edit.missing",
                      entry.name()))), project.root());
      String path = project.root().relativize(copy).toString().replace('\\', '/');
      if (entry.managed()) {
        write(source -> SpriteAssets.useFile(source, entry, path));
      } else {
        alert(I18n.t("spriteassets.edit.handwritten", path));
      }
      onEditFile.accept(copy);
    } catch (IOException | RuntimeException e) {
      alert(e.getMessage(), e);
    }
  }

  private void add(SpriteAssets.Kind kind) {
    if (kind == SpriteAssets.Kind.ANIMATION) {
      animationDialog();
      return;
    }
    var chosen = kind == SpriteAssets.Kind.COSTUME
        ? AssetPickerDialog.pickImage(project.root(), I18n.t("spriteassets.costumes"))
        : AssetPickerDialog.pickSound(project.root(), I18n.t("spriteassets.sounds"));
    chosen.ifPresent(reference -> {
      boolean fileReference = isProjectFile(reference);
      String name = fileReference ? reference.substring(reference.lastIndexOf('/') + 1)
          .replaceFirst("\\.[^.]+$", "") : reference;
      write(source -> SpriteAssets.add(source, kind, name,
          fileReference ? reference : null, 0));
    });
  }

  /**
   * A picked project file ("sprites/cat.png", anywhere in the project) rather
   * than a built-in name: built-in names have no file extension.
   */
  private static boolean isProjectFile(String reference) {
    return reference.matches("(?i).+\\.[a-z0-9]+$");
  }

  private void animationDialog() {
    TextField name = new TextField();
    TextField pattern = new TextField();
    pattern.setPromptText("bunny1_walk%d");
    TextField count = new TextField("2");
    Button pick = new Button(I18n.t("spriteassets.pick.frame"));
    pick.setOnAction(e -> AssetPickerDialog.pickImage(project.root(),
        I18n.t("spriteassets.pick.frame")).ifPresent(ref -> {
          pattern.setText(ref.replaceFirst("\\d+(?=\\.[^.]+$|$)", "%d"));
          if (name.getText().isBlank()) {
            name.setText("animation");
          }
        }));
    VBox fields = new VBox(7,
        new Label(I18n.t("spriteassets.name")), name,
        new Label(I18n.t("spriteassets.pattern")), pattern, pick,
        new Label(I18n.t("spriteassets.frames")), count);
    Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
    dialog.setTitle(I18n.t("spriteassets.animations"));
    Theme.style(dialog);
    dialog.getDialogPane().setContent(fields);
    dialog.getDialogPane().getButtonTypes().addAll(I18n.ok(), I18n.cancel());
    if (dialog.showAndWait().orElse(null) == I18n.ok()) {
      try {
        int frames = Integer.parseInt(count.getText().trim());
        write(source -> SpriteAssets.add(source, SpriteAssets.Kind.ANIMATION,
            name.getText().trim(), pattern.getText().trim(), frames));
      } catch (NumberFormatException e) {
        alert(I18n.t("spriteassets.frames.invalid"));
      }
    }
  }

  private VBox hitboxPane() {
    hitboxCostume.setPromptText(I18n.t("spriteassets.hitbox.image"));
    hitboxCostume.setOnAction(e -> {
      hitboxImage = CostumeView.costume(hitboxCostume.getValue(), project.root());
      drawHitbox();
    });
    pointList.setPrefSize(185, 300);
    pointList.getSelectionModel().selectedIndexProperty().addListener((o, old, index) -> {
      selectedPoint = index.intValue();
      updatePointFields();
      drawHitbox();
    });
    pointX.setPrefColumnCount(5);
    pointY.setPrefColumnCount(5);
    for (TextField field : List.of(pointX, pointY)) {
      field.setOnAction(e -> applyPointFields());
      field.focusedProperty().addListener((o, old, focused) -> {
        if (!focused) applyPointFields();
      });
    }
    hitboxCanvas.setFocusTraversable(true);
    hitboxCanvas.setOnMousePressed(e -> {
      if (hitboxReadOnly || hitboxImage == null) return;
      Point2D point = toImage(e.getX(), e.getY());
      if (point == null) return;
      dragging = nearest(point);
      dragRecorded = false;
      if (e.isSecondaryButtonDown()) {
        if (dragging >= 0) {
          selectedPoint = dragging;
          removeSelectedPoint();
        }
        dragging = -1;
      } else if (dragging >= 0) {
        selectedPoint = dragging;
      } else {
        recordHitboxUndo();
        int edge = HitboxPolygon.nearestEdge(polygon(),
            new HitboxPolygon.Point(point.getX(), point.getY()), 10 / imageScale);
        selectedPoint = edge < 0 ? vertices.size() : edge + 1;
        vertices.add(selectedPoint, point);
        dragging = selectedPoint;
        hitboxChanged();
      }
      if (dragging >= 0) hitboxCanvas.setCursor(javafx.scene.Cursor.CLOSED_HAND);
      refreshPoints();
      drawHitbox();
      hitboxCanvas.requestFocus();
    });
    hitboxCanvas.setOnMouseDragged(e -> {
      if (dragging >= 0 && !hitboxReadOnly) {
        Point2D point = toImage(e.getX(), e.getY());
        if (point != null && !vertices.get(dragging).equals(point)) {
          if (!dragRecorded) {
            recordHitboxUndo();
            dragRecorded = true;
          }
          vertices.set(dragging, point);
          hitboxChanged();
          refreshPoints();
          drawHitbox();
        }
      }
    });
    hitboxCanvas.setOnMouseReleased(e -> {
      dragging = -1;
      hitboxCanvas.setCursor(hitboxCursor(e.getX(), e.getY()));
      if (hitboxDirty) hitboxSave.playFromStart();
    });
    // a hand over a point (drag it), a crosshair where a click adds one
    hitboxCanvas.setOnMouseMoved(e -> hitboxCanvas.setCursor(hitboxCursor(e.getX(), e.getY())));
    hitboxCanvas.setOnKeyPressed(e -> {
      if (hitboxKey(e.getCode(), e.isShiftDown())) e.consume();
    });
    pointList.setOnKeyPressed(e -> {
      if (hitboxKey(e.getCode(), e.isShiftDown())) e.consume();
    });
    Button rectangle = new Button(I18n.t("spriteassets.rectangle"));
    rectangle.setOnAction(e -> {
      if (hitboxImage == null || hitboxReadOnly) return;
      double w = hitboxImage.getWidth(), h = hitboxImage.getHeight();
      recordHitboxUndo();
      vertices.clear();
      vertices.addAll(List.of(new Point2D(0, 0), new Point2D(w, 0),
          new Point2D(w, h), new Point2D(0, h)));
      selectedPoint = 0;
      hitboxChanged();
      refreshPoints();
      drawHitbox();
    });
    // no Save button: a valid shape saves itself (saveHitboxNow)
    hitboxSave.setOnFinished(e -> saveHitboxNow());
    Button clear = new Button(I18n.t("spriteassets.clear.hitbox"));
    clear.setOnAction(e -> {
      hitboxSave.stop();
      if (!hitboxReadOnly) write(source -> {
        if (!java.util.Arrays.equals(SpriteAssets.hitbox(source), loadedHitbox)) {
          throw new IllegalStateException(I18n.t("spriteassets.hitbox.conflict"));
        }
        return SpriteRegionWriter.removeHitbox(source);
      }, true);
    });
    Button removePoint = new Button(I18n.t("spriteassets.point.remove"));
    removePoint.setOnAction(e -> removeSelectedPoint());
    removePointButton = removePoint;
    Button undo = new Button(I18n.t("spriteassets.hitbox.undo"));
    undo.setOnAction(e -> undoHitbox());
    Button redo = new Button(I18n.t("spriteassets.hitbox.redo"));
    redo.setOnAction(e -> redoHitbox());
    pointsLabel = new Label(I18n.t("spriteassets.points"));
    pointRow = new HBox(5, new Label("X"), pointX, new Label("Y"), pointY);
    pointRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    VBox pointControls = new VBox(7, pointsLabel, pointList, pointRow, removePoint);
    Label underlay = new Label(I18n.t("spriteassets.hitbox.underlay"));
    underlay.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
    HBox underlayRow = new HBox(8, underlay, hitboxCostume);
    underlayRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
    VBox pane = new VBox(8, hitboxHelp, underlayRow,
        new HBox(10, hitboxCanvas, pointControls),
        new HBox(8, rectangle, undo, redo, clear), hitboxState);
    pane.setPadding(new Insets(8));
    return pane;
  }

  /** The points the editor shows, as the code writes them. */
  private double[] shownHitbox() {
    double[] points = new double[vertices.size() * 2];
    for (int i = 0; i < vertices.size(); i++) {
      points[i * 2] = vertices.get(i).getX();
      points[i * 2 + 1] = vertices.get(i).getY();
    }
    return points;
  }

  private Point2D toImage(double x, double y) {
    double px = (x - imageX) / imageScale;
    double py = (y - imageY) / imageScale;
    if (px < 0 || py < 0 || px > hitboxImage.getWidth() || py > hitboxImage.getHeight()) {
      return null;
    }
    return new Point2D(Math.round(px), Math.round(py));
  }

  /** The arrow where the hitbox can't change, else a hand over a point or a crosshair. */
  private javafx.scene.Cursor hitboxCursor(double canvasX, double canvasY) {
    if (hitboxReadOnly || hitboxImage == null) return javafx.scene.Cursor.DEFAULT;
    Point2D point = toImage(canvasX, canvasY);
    if (point == null) return javafx.scene.Cursor.DEFAULT;
    return nearest(point) >= 0 ? javafx.scene.Cursor.OPEN_HAND : javafx.scene.Cursor.CROSSHAIR;
  }

  private int nearest(Point2D point) {
    for
 (int i = 0; i < vertices.size(); i++) {
      if (vertices.get(i).distance(point) * imageScale < 10) return i;
    }
    return -1;
  }

  private List<HitboxPolygon.Point> polygon() {
    return vertices.stream().map(p -> new HitboxPolygon.Point(p.getX(), p.getY())).toList();
  }

  private void recordHitboxUndo() {
    hitboxUndo.push(new ArrayList<>(vertices));
    if (hitboxUndo.size() > 50) hitboxUndo.removeLast();
    hitboxRedo.clear();
  }

  private void undoHitbox() {
    if (hitboxReadOnly || hitboxUndo.isEmpty()) return;
    hitboxRedo.push(new ArrayList<>(vertices));
    vertices.clear();
    vertices.addAll(hitboxUndo.pop());
    selectedPoint = Math.min(selectedPoint, vertices.size() - 1);
    hitboxChanged();
    refreshPoints();
    drawHitbox();
  }

  private void redoHitbox() {
    if (hitboxReadOnly || hitboxRedo.isEmpty()) return;
    hitboxUndo.push(new ArrayList<>(vertices));
    vertices.clear();
    vertices.addAll(hitboxRedo.pop());
    selectedPoint = Math.min(selectedPoint, vertices.size() - 1);
    hitboxChanged();
    refreshPoints();
    drawHitbox();
  }

  private void removeSelectedPoint() {
    if (hitboxReadOnly || selectedPoint < 0 || selectedPoint >= vertices.size()) return;
    recordHitboxUndo();
    vertices.remove(selectedPoint);
    selectedPoint = Math.min(selectedPoint, vertices.size() - 1);
    hitboxChanged();
    refreshPoints();
    drawHitbox();
  }

  private boolean hitboxKey(KeyCode key, boolean shift) {
    if (hitboxReadOnly) return false;
    if (key == KeyCode.DELETE || key == KeyCode.BACK_SPACE) {
      removeSelectedPoint();
      return true;
    }
    if (selectedPoint < 0 || selectedPoint >= vertices.size() || hitboxImage == null) return false;
    int step = shift ? 10 : 1;
    int dx = key == KeyCode.LEFT ? -step : key == KeyCode.RIGHT ? step : 0;
    int dy = key == KeyCode.UP ? -step : key == KeyCode.DOWN ? step : 0;
    if (dx == 0 && dy == 0) return false;
    Point2D old = vertices.get(selectedPoint);
    Point2D next = new Point2D(Math.max(0, Math.min(hitboxImage.getWidth(), old.getX() + dx)),
        Math.max(0, Math.min(hitboxImage.getHeight(), old.getY() + dy)));
    if (!next.equals(old)) {
      recordHitboxUndo();
      vertices.set(selectedPoint, next);
      hitboxChanged();
      refreshPoints();
      drawHitbox();
    }
    return true;
  }

  private void refreshPoints() {
    int keep = selectedPoint;
    List<String> labels = new ArrayList<>();
    for (int i = 0; i < vertices.size(); i++) {
      Point2D point = vertices.get(i);
      labels.add((i + 1) + ":  " + Math.round(point.getX()) + ", "
          + Math.round(point.getY()));
    }
    pointList.getItems().setAll(labels);
    if (keep >= 0 && keep < labels.size()) {
      pointList.getSelectionModel().select(keep);
      selectedPoint = keep;
    }
    updatePointFields();
    // the list once there are points; X/Y and Remove for the selected one
    boolean any = !vertices.isEmpty();
    for (javafx.scene.Node node : List.of(pointsLabel, pointList)) {
      node.setVisible(any);
      node.setManaged(any);
    }
    refreshHitboxState();
  }

  private void updatePointFields() {
    updatingPointFields = true;
    try {
      Point2D point = selectedPoint >= 0 && selectedPoint < vertices.size()
          ? vertices.get(selectedPoint) : null;
      pointX.setText(point == null ? "" : String.valueOf(Math.round(point.getX())));
      pointY.setText(point == null ? "" : String.valueOf(Math.round(point.getY())));
      pointX.setDisable(point == null || hitboxReadOnly);
      pointY.setDisable(point == null || hitboxReadOnly);
      if (pointRow != null) {
        for (javafx.scene.Node node : List.of(pointRow, removePointButton)) {
          node.setVisible(point != null);
          node.setManaged(point != null);
        }
      }
    } finally {
      updatingPointFields = false;
    }
  }

  private void applyPointFields() {
    if (updatingPointFields || hitboxReadOnly || hitboxImage == null
        || selectedPoint < 0 || selectedPoint >= vertices.size()) return;
    try {
      double x = Double.parseDouble(pointX.getText().trim());
      double y = Double.parseDouble(pointY.getText().trim());
      if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0
          || x > hitboxImage.getWidth() || y > hitboxImage.getHeight()) {
        throw new NumberFormatException();
      }
      Point2D next = new Point2D(x, y);
      if (!next.equals(vertices.get(selectedPoint))) {
        recordHitboxUndo();
        vertices.set(selectedPoint, next);
        hitboxChanged();
        refreshPoints();
        drawHitbox();
      }
    } catch (NumberFormatException e) {
      alert(I18n.t("spriteassets.hitbox.coordinate"));
      updatePointFields();
    }
  }

  private void drawHitbox() {
    var g = hitboxCanvas.getGraphicsContext2D();
    g.setFill(Color.web("#e6e8ee"));
    g.fillRect(0, 0, hitboxCanvas.getWidth(), hitboxCanvas.getHeight());
    if (hitboxImage == null) return;
    // small pixel-art costumes big enough to place points precisely
    imageScale = Math.min(12, Math.min(500 / hitboxImage.getWidth(),
        350 / hitboxImage.getHeight()));
    imageX = (hitboxCanvas.getWidth() - hitboxImage.getWidth() * imageScale) / 2;
    imageY = (hitboxCanvas.getHeight() - hitboxImage.getHeight() * imageScale) / 2;
    // pixel art stays sharp (and no colour bleeds in from the next sheet cell)
    g.setImageSmoothing(imageScale < 1);
    g.drawImage(hitboxImage, imageX, imageY,
        hitboxImage.getWidth() * imageScale, hitboxImage.getHeight() * imageScale);
    if (vertices.size() >= 3) {
      double[] xs = new double[vertices.size()], ys = new double[vertices.size()];
      for (int i = 0; i < vertices.size(); i++) {
        xs[i] = imageX + vertices.get(i).getX() * imageScale;
        ys[i] = imageY + vertices.get(i).getY() * imageScale;
      }
      g.setFill(Color.rgb(255, 120, 0, 0.16));
      g.fillPolygon(xs, ys, xs.length);
    }
    g.setStroke(Color.ORANGE);
    g.setLineWidth(2);
    for (int i = 0; i < vertices.size(); i++) {
      Point2D a = vertices.get(i), b = vertices.get((i + 1) % vertices.size());
      g.strokeLine(imageX + a.getX() * imageScale, imageY + a.getY() * imageScale,
          imageX + b.getX() * imageScale, imageY + b.getY() * imageScale);
    }
    g.setFill(Color.web("#ff7800"));
    for (int i = 0; i < vertices.size(); i++) {
      Point2D p = vertices.get(i);
      g.setFill(i == selectedPoint ? Color.web("#0678d6") : Color.web("#ff7800"));
      g.fillOval(imageX + p.getX() * imageScale - 5,
          imageY + p.getY() * imageScale - 5, 10, 10);
      g.setFill(Color.BLACK);
      g.fillText(Integer.toString(i + 1), imageX + p.getX() * imageScale + 7,
          imageY + p.getY() * imageScale - 5);
    }
  }

  private void write(UnaryOperator<String> edit) {
    write(edit, false);
  }

  private void write(UnaryOperator<String> edit, boolean resetHitbox) {
    try {
      String source = currentSource();
      String updated = edit.apply(source);
      if (!updated.equals(source)) {
        LocalHistory.writeString(project.root(), file, updated);
      }
      if (resetHitbox) hitboxDirty = false;
      if (!updated.equals(source)) onSaved.run();
      reload();
    } catch (IOException | RuntimeException e) {
      alert(e.getMessage(), e);
    }
  }

  private java.util.function.Supplier<String> sourceSupplier;

  void setSourceSupplier(java.util.function.Supplier<String> supplier) {
    sourceSupplier = supplier;
    reload();
  }

  private String currentSource() throws IOException {
    return sourceSupplier == null ? Files.readString(file) : sourceSupplier.get();
  }

  private void alert(String message) {
    alert(message, null);
  }

  private void alert(String message, Throwable e) {
    Alert alert = ErrorDetails.attach(new Alert(Alert.AlertType.ERROR, message, I18n.ok()), e);
    Theme.style(alert);
    alert.showAndWait();
  }

  /** Not an error: why an entry is read-only here. */
  private void inform(String message) {
    Alert info = new Alert(Alert.AlertType.INFORMATION, message, I18n.ok());
    info.setHeaderText(null);
    Theme.style(info);
    info.showAndWait();
  }
}
