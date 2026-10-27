package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import java.util.List;
import java.util.ArrayList;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.FlowPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Separator;
import javafx.geometry.Pos;
import javafx.scene.Node;
import org.openpatch.scratch4j.sound.MicrophoneRecorder;
import org.openpatch.scratch4j.sound.SoundClip;
import org.openpatch.scratch4j.sound.SoundEffects;
import org.openpatch.scratch4j.sound.SoundIO;
import org.openpatch.scratch4j.sound.SoundPlayer;
import org.openpatch.scratch4j.core.io.AtomicFiles;
import org.openpatch.scratch4j.core.io.EditorHistory;
import org.openpatch.scratch4j.core.io.LocalHistory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The sound editor: waveform view, play/loop selection, cut/copy/paste/
 * delete/trim, insert silence, fades, louder/softer, normalize, reverse,
 * faster/slower, undo/redo, microphone recording (with a live waveform and
 * a choice of device), WAV save. MP3 files open
 * for editing and save as WAV (the library cannot play MP3).
 */
final class SoundEditorView extends BorderPane implements SavableTool {

  private final Path file;
  private final Path projectRoot;
  private final javafx.scene.canvas.Canvas wave =
      new javafx.scene.canvas.Canvas(900, 120);
  private final Label status = new Label();

  private SoundClip clip;
  private SoundEffects.Selection selection;
  private SoundClip clipboard;
  private final SoundPlayer player = new SoundPlayer();
  private final ToggleButton loopPlayback = new ToggleButton(I18n.t("soundeditor.loop"));
  private final MicrophoneRecorder recorder = new MicrophoneRecorder();
  private final Button record = new Button();
  /** Play, Pause while a sound plays, and Resume after a pause. */
  private final Button playPause = new Button();
  /** Says what the effects change: the selection or the whole sound. */
  private final Label scope = new Label();
  /** Buttons that need a selection, and Paste, which needs something copied. */
  private final List<Button> needSelection = new ArrayList<>();
  private Button pasteButton;
  /** What the transport buttons show (-1 before the first update). */
  private int shownTransport = -1;
  /** Where playback was paused (Play resumes there), or -1. */
  private int pausedAt = -1;
  /** The recording devices; the first entry is the default microphone. */
  private final javafx.scene.control.ComboBox<MicrophoneRecorder.Device> device =
      new javafx.scene.control.ComboBox<>();
  private static final MicrophoneRecorder.Device DEFAULT_DEVICE =
      new MicrophoneRecorder.Device("", I18n.t("soundeditor.device.default"));
  /** Set while the list is filled in, so that does not count as the user's choice. */
  private boolean fillingDevices;
  /** Redraws the waveform while recording (it grows) or playing (the playhead moves). */
  private final javafx.animation.AnimationTimer liveWave = new javafx.animation.AnimationTimer() {
    @Override
    public void handle(long now) {
      if (!recorder.isRecording() && !player.isPlaying()) {
        stop();
      }
      followPlayhead();
      redraw();
    }
  };
  private static final float RECORD_RATE = 44100;
  /** Height of the seconds ruler under the waveform. */
  private static final int RULER = 20;
  /** Seconds the view shows at least, so short sounds look short. */
  private static final double MIN_VIEW = 10;
  /** Zoom step for Ctrl + wheel and the zoom buttons. */
  private static final double ZOOM_STEP = 1.25;
  /** The part of the timeline in view, in seconds. */
  private double viewStart;
  private double viewSpan = MIN_VIEW;
  private final javafx.scene.control.ScrollBar timeScroll = new javafx.scene.control.ScrollBar();
  /** Set while the view moves the scroll bar, so the bar does not move the view back. */
  private boolean movingScroll;

  private final UndoHistory<SoundClip> history = new UndoHistory<>(40);
  // edits since the last save: the tab shows a marker
  private final javafx.beans.property.BooleanProperty dirty =
      new javafx.beans.property.SimpleBooleanProperty();

  /** Selection in canvas x coordinates while dragging. */
  private double dragStartX = -1;

  SoundEditorView(Path projectRoot, Path file) throws IOException {
    this.projectRoot = projectRoot;
    this.file = file;
    this.clip = SoundIO.decode(file);
    loadHistory();
    getStyleClass().add("sound-editor");
    // a closed tab falls silent and lets go of the microphone, however it closed (its
    // close button, the file moved or deleted, another project); switching to another
    // tab keeps the editor in the scene, so the sound plays on there
    sceneProperty().addListener((o, old, scene) -> {
      if (scene == null) {
        player.stop();
        if (recorder.isRecording()) {
          recorder.stop();
          recordingStopped();
        }
      }
    });

    // the usual shortcuts anywhere in the editor (Ctrl is Cmd on macOS)
    addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
      if (EditorKeys.isEditorKey(e)
          && shortcut(e.getCode(), e.isShortcutDown(), e.isShiftDown())) {
        e.consume();
      }
    });

    wave.setFocusTraversable(true);
    wave.setOnMousePressed(e -> {
      if (recorder.isRecording()) return;
      wave.requestFocus(); // the shortcuts need the focus in the editor
      dragStartX = e.getX();
      selection = null;
      pausedAt = -1;
      redraw();
    });
    wave.setOnMouseDragged(e -> {
      if (clip.sampleCount() == 0 || recorder.isRecording()) {
        return;
      }
      int start = sampleAt(Math.min(dragStartX, e.getX()));
      int end = sampleAt(Math.max(dragStartX, e.getX()));
      selection = end > start ? new SoundEffects.Selection(start, end) : null;
      redraw();
    });
    wave.setOnMouseReleased(e -> dragStartX = -1);
    wave.setCursor(javafx.scene.Cursor.TEXT);
    // Ctrl + wheel (or a pinch) zooms around the mouse, the wheel alone scrolls
    wave.setOnScroll(e -> {
      if (recorder.isRecording()) return;
      if (e.isShortcutDown()) {
        if (e.getDeltaY() != 0) zoomAround(e.getX(), e.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP);
      } else {
        double delta = e.getDeltaX() != 0 ? e.getDeltaX() : e.getDeltaY();
        showView(viewStart - delta / wave.getWidth() * viewSpan, viewSpan);
      }
      e.consume();
    });
    wave.setOnZoom(e -> {
      if (!recorder.isRecording()) zoomAround(e.getX(), e.getZoomFactor());
      e.consume();
    });
    timeScroll.valueProperty().addListener((o, old, value) -> {
      if (!movingScroll) showView(value.doubleValue(), viewSpan);
    });
    moveView(0, MIN_VIEW);

    // the canvas is unmanaged: the box takes the tab's size, the canvas follows it
    wave.setManaged(false);
    wave.relocate(1, 1);
    javafx.scene.layout.Pane waveBox = new javafx.scene.layout.Pane(wave);
    waveBox.getStyleClass().add("wave-box");
    waveBox.setMinSize(0, 140);
    waveBox.setPrefSize(400, 220);
    // the waveform fills the space the tab gives it
    waveBox.widthProperty().addListener((o, old, w) -> resizeWave(waveBox));
    waveBox.heightProperty().addListener((o, old, h) -> resizeWave(waveBox));

    setTop(transportBar());
    VBox.setVgrow(waveBox, Priority.ALWAYS);
    VBox timeline = new VBox(2, waveBox, timeScroll);
    setCenter(timeline);
    BorderPane.setMargin(timeline, new Insets(12, 12, 8, 12));
    HBox statusBar = new HBox(8, status);
    statusBar.getStyleClass().add("editor-status");
    setBottom(new VBox(effectsPanel(), statusBar));
    redraw();
  }

  private void resizeWave(Region box) {
    wave.setWidth(Math.max(100, box.getWidth() - 2));
    wave.setHeight(Math.max(100, box.getHeight() - 2));
    redraw();
  }

  Path file() {
    return file;
  }

  private int sampleAt(double x) {
    int sample = (int) Math.round(timeAt(x) * clip.sampleRate());
    return Math.max(0, Math.min(clip.sampleCount() - 1, sample));
  }

  // --- the part of the timeline in view ------------------------------------

  /** The timeline's length: the sound, but at least {@link #MIN_VIEW} seconds. */
  private double timelineLength() {
    return Math.max(MIN_VIEW, clip.duration());
  }

  private double timeAt(double x) {
    return viewStart + x / wave.getWidth() * viewSpan;
  }

  private double xAt(double seconds) {
    return (seconds - viewStart) / viewSpan * wave.getWidth();
  }

  /** Moves the view (kept on the timeline, at most fully zoomed out) without drawing. */
  private void moveView(double start, double span) {
    double length = timelineLength();
    // zoomed in at most to 50 samples across the waveform
    viewSpan = Math.max(Math.min(span, length), Math.min(length, 50 / clip.sampleRate()));
    viewStart = Math.max(0, Math.min(start, length - viewSpan));
    movingScroll = true;
    try {
      timeScroll.setMin(0);
      timeScroll.setMax(length - viewSpan);
      timeScroll.setVisibleAmount(viewSpan * (length - viewSpan) / length);
      timeScroll.setValue(viewStart);
      timeScroll.setUnitIncrement(viewSpan / 20);
      timeScroll.setBlockIncrement(viewSpan * 0.9);
      timeScroll.setDisable(viewSpan >= length);
    } finally {
      movingScroll = false;
    }
  }

  private void showView(double start, double span) {
    moveView(start, span);
    redraw();
  }

  /** Zooms in (factor above 1) or out, keeping the moment under x in place. */
  private void zoomAround(double x, double factor) {
    double at = timeAt(x);
    double span = viewSpan / factor;
    showView(at - x / wave.getWidth() * span, span);
  }

  /** The whole sound fills the waveform (short sounds too). */
  private void fitView() {
    showView(0, clip.duration() > 0 ? clip.duration() : MIN_VIEW);
  }

  /** While playing, the view pages along so the playhead stays in sight. */
  private void followPlayhead() {
    int position = player.position();
    if (position < 0 || dragStartX >= 0) return;
    double at = position / clip.sampleRate();
    if (at < viewStart || at > viewStart + viewSpan) {
      moveView(at - viewSpan * 0.05, viewSpan);
    }
  }

  private Node transportBar() {
    playPause.getStyleClass().add("transport-button");
    playPause.setOnAction(e -> playOrPause());
    playPause.setTooltip(new Tooltip(I18n.t("soundeditor.play.tooltip")));
    loopPlayback.setTooltip(new Tooltip(I18n.t("soundeditor.loop.tooltip")));
    loopPlayback.setGraphic(Icons.of("fth-repeat"));
    record.getStyleClass().add("transport-button");
    record.setOnAction(e -> record());
    deviceChooser();
    Button save = new Button(I18n.t("soundeditor.save"), Icons.of("fth-save"));
    save.getStyleClass().add("accent");
    save.setOnAction(e -> save());
    save.setTooltip(new Tooltip(I18n.t("editor.save.tooltip")));
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    for (Button b : List.of(playPause, record, save)) {
      b.setMinWidth(Region.USE_PREF_SIZE);
    }
    loopPlayback.setMinWidth(Region.USE_PREF_SIZE);
    device.setMinWidth(80);
    updateTransport();
    Button toStart = Icons.button("fth-skip-back", I18n.t("soundeditor.tostart"), this::toStart);
    HBox bar = new HBox(6, toStart, playPause, loopPlayback,
        new Separator(javafx.geometry.Orientation.VERTICAL), record, device,
        new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("fth-corner-up-left", I18n.t("soundeditor.undo"), this::undo),
        Icons.button("fth-corner-up-right", I18n.t("soundeditor.redo"), this::redo),
        new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("fth-zoom-out", I18n.t("soundeditor.zoomout"),
            () -> zoomAround(wave.getWidth() / 2, 1 / ZOOM_STEP)),
        Icons.button("fth-zoom-in", I18n.t("soundeditor.zoomin"),
            () -> zoomAround(wave.getWidth() / 2, ZOOM_STEP)),
        Icons.button("fth-maximize-2", I18n.t("soundeditor.zoomfit"), this::fitView),
        spacer, save);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("tool-bar-row");
    return bar;
  }

  /**
   * Play turns into a red Pause while a sound plays (and Resume after a
   * pause), Record into Stop recording while recording; the other one waits
   * meanwhile.
   */
  private void updateTransport() {
    int state = recorder.isRecording() ? 2 : player.isPlaying() ? 1 : pausedAt >= 0 ? 3 : 0;
    if (state == shownTransport) return;
    shownTransport = state;
    boolean playing = state == 1;
    boolean recording = state == 2;
    playPause.setText(I18n.t(playing ? "soundeditor.pause"
        : state == 3 ? "soundeditor.resume" : "soundeditor.play"));
    playPause.setGraphic(Icons.of(playing ? "fth-pause" : "fth-play", 16));
    playPause.getStyleClass().removeAll("success", "danger", "transport-active");
    playPause.getStyleClass().addAll(playing ? List.of("danger", "transport-active")
        : List.of("success"));
    playPause.setDisable(recording);
    record.setText(I18n.t(recording ? "soundeditor.stoprecord" : "soundeditor.record"));
    record.setGraphic(Icons.of(recording ? "fth-square" : "fth-mic", 16));
    // outlined while idle, so the filled red Stop recording stands out
    record.getStyleClass().removeAll("danger", "button-outlined", "transport-active");
    record.getStyleClass().addAll(recording ? List.of("danger", "transport-active")
        : List.of("danger", "button-outlined"));
    device.setDisable(recording);
  }

  /** Pauses where the sound is, or plays (from the pause, if there was one). */
  private void playOrPause() {
    if (player.isPlaying()) {
      pausedAt = player.position();
      player.stop();
      status.setText("");
      redraw();
    } else {
      play();
    }
  }

  /** Back to the start of the selection (or the sound); playing goes on from there. */
  private void toStart() {
    if (recorder.isRecording()) return;
    pausedAt = -1;
    boolean wasPlaying = player.isPlaying();
    double start = selection == null ? 0 : selection.start() / clip.sampleRate();
    if (start < viewStart || start > viewStart + viewSpan) {
      moveView(start, viewSpan);
    }
    if (wasPlaying) {
      play();
    } else {
      redraw();
    }
  }

  /**
   * The edit and effect buttons under the waveform, in labelled groups that
   * wrap when the tab is narrow, with a note on what they change.
   */
  private Node effectsPanel() {
    needSelection.clear();
    Button cut = tile("soundeditor.cut", "fth-scissors", () -> cut(selection));
    Button copy = tile("soundeditor.copy", "fth-copy", () -> copy(selection));
    pasteButton = tile("soundeditor.paste", "fth-clipboard", this::paste);
    Button delete = tile("soundeditor.delete", "fth-trash-2", () -> delete(selection));
    Button trim = tile("soundeditor.trim", "fth-crop", () ->
        setClip(SoundEffects.trim(clip, selection), true));
    needSelection.addAll(List.of(cut, copy, delete, trim));
    Button silence = tile("soundeditor.silence", "fth-volume-x", () -> {
      int at = selection == null ? clip.sampleCount() : selection.start();
      setClip(SoundEffects.insertSilence(clip, at, 0.5), true);
    });
    silence.setTooltip(new Tooltip(I18n.t("soundeditor.silence.tooltip")));

    Node edit = group("soundeditor.group.edit", cut, copy, pasteButton, delete, trim, silence);
    Node volume = group("soundeditor.group.volume",
        effectTile("soundeditor.louder", "fth-volume-2", (c, sel) -> SoundEffects.volume(c, sel, 1.25)),
        effectTile("soundeditor.softer", "fth-volume-1", (c, sel) -> SoundEffects.volume(c, sel, 0.8)),
        effectTile("soundeditor.fadein", "fth-trending-up", SoundEffects::fadeIn),
        effectTile("soundeditor.fadeout", "fth-trending-down", SoundEffects::fadeOut),
        effectTile("soundeditor.normalize", "fth-bar-chart-2", SoundEffects::normalize));
    Node effects = group("soundeditor.group.effects",
        effectTile("soundeditor.faster", "fth-fast-forward", (c, sel) -> speed(c, sel, 1.25)),
        effectTile("soundeditor.slower", "fth-clock", (c, sel) -> speed(c, sel, 0.8)),
        effectTile("soundeditor.reverse", "fth-rewind", SoundEffects::reverse),
        effectTile("soundeditor.echo", "fth-radio", SoundEffects::echo),
        effectTile("soundeditor.robot", "fth-cpu", SoundEffects::robot));

    scope.getStyleClass().add("effect-scope");
    FlowPane groups = new FlowPane(10, 10, edit, volume, effects);
    groups.setMinWidth(0);
    VBox panel = new VBox(8, scope, groups);
    panel.getStyleClass().add("effects-panel");
    return panel;
  }

  /** A labelled group of tiles. */
  private static Node group(String key, Button... tiles) {
    Label title = new Label(I18n.t(key));
    title.getStyleClass().add("effect-group-title");
    HBox row = new HBox(2, tiles);
    VBox box = new VBox(4, title, row);
    box.getStyleClass().add("effect-group");
    return box;
  }

  /** A button with its icon above its name. */
  private Button tile(String key, String icon, Runnable action) {
    Button button = new Button(I18n.t(key), Icons.of(icon, 20));
    button.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
    button.getStyleClass().addAll("flat", "effect-tile");
    button.setMinWidth(Region.USE_PREF_SIZE);
    button.setOnAction(e -> {
      action.run();
      wave.requestFocus(); // the shortcuts keep working after a click
    });
    return button;
  }

  /**
   * An effect tile: changes the selection, or the whole sound without one
   * (the effect gets null). The changed part stays selected, so the effect
   * can be applied again.
   */
  private Button effectTile(String key, String icon,
      java.util.function.BiFunction<SoundClip, SoundEffects.Selection, SoundClip> effect) {
    return tile(key, icon, () -> {
      if (clip.sampleCount() == 0) return;
      SoundEffects.Selection range = selection;
      int before = clip.sampleCount();
      setClip(effect.apply(clip, range), true);
      int length = range == null ? 0 : range.length() + clip.sampleCount() - before;
      if (length > 1) {
        selection = new SoundEffects.Selection(range.start(), range.start() + length - 1);
        redraw();
      }
    });
  }

  /** Faster or slower: the selection gets shorter or longer, the rest stays. */
  private static SoundClip speed(SoundClip clip, SoundEffects.Selection range, double factor) {
    if (range == null) return SoundEffects.speed(clip, factor);
    SoundClip changed = SoundEffects.speed(SoundEffects.copy(clip, range), factor);
    return SoundEffects.paste(SoundEffects.delete(clip, range), range.start(), changed);
  }

  /** Enables the buttons that fit the selection and says what the effects change. */
  private void updateActions() {
    boolean selected = selection != null;
    for (Button b : needSelection) b.setDisable(!selected || recorder.isRecording());
    if (pasteButton != null) pasteButton.setDisable(clipboard == null || recorder.isRecording());
    if (selected) {
      scope.setText(I18n.t("soundeditor.scope.selection", String.format(java.util.Locale.ROOT,
          "%.2f", selection.length() / clip.sampleRate())));
      scope.getStyleClass().add("selected");
    } else {
      scope.setText(I18n.t(clip.sampleCount() == 0 ? "soundeditor.scope.empty"
          : "soundeditor.scope.all"));
      scope.getStyleClass().removeAll("selected");
    }
  }

  private void cut(SoundEffects.Selection sel) {
    clipboard = SoundEffects.copy(clip, sel);
    setClip(SoundEffects.cut(clip, sel), true);
  }

  private void copy(SoundEffects.Selection sel) {
    clipboard = SoundEffects.copy(clip, sel);
    updateActions(); // Paste can be used now
  }

  /** Pastes at the selection's start, or at the end without a selection. */
  private void paste() {
    if (clipboard != null) {
      int at = selection == null ? clip.sampleCount() : selection.start();
      setClip(SoundEffects.paste(clip, at, clipboard), true);
    }
  }

  private void delete(SoundEffects.Selection sel) {
    setClip(SoundEffects.delete(clip, sel), true);
  }

  /**
   * One shortcut (Ctrl is Cmd on macOS); returns whether it was handled.
   * Save, undo/redo, select all, copy/cut/paste; Space plays or pauses, Home
   * goes back to the start, Delete removes the selection, Escape stops and
   * clears it. The keys that change
   * the sound need a selection: a button press applies to the whole sound.
   */
  boolean shortcut(javafx.scene.input.KeyCode code, boolean ctrl, boolean shift) {
    if (ctrl) {
      switch (code) {
        case S -> save();
        case Z -> {
          if (shift) redo(); else undo();
        }
        case Y -> redo();
        case A -> {
          if (clip.sampleCount() > 1) {
            selection = new SoundEffects.Selection(0, clip.sampleCount() - 1);
            pausedAt = -1;
            redraw();
          }
        }
        case C -> {
          if (selection != null) copy(selection);
        }
        case X -> {
          if (selection != null) cut(selection);
        }
        case V -> paste();
        default -> {
          return false;
        }
      }
      return true;
    }
    switch (code) {
      case SPACE -> playOrPause();
      case HOME -> toStart();
      case DELETE, BACK_SPACE -> {
        if (selection == null) return false;
        delete(selection);
      }
      case ESCAPE -> {
        player.stop();
        selection = null;
        pausedAt = -1;
        redraw();
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  // --- clip state ----------------------------------------------------------

  @Override
  public javafx.beans.property.ReadOnlyBooleanProperty dirtyProperty() {
    return dirty;
  }

  @Override
  public String unsavedCloseQuestion() {
    return I18n.t("soundeditor.close.unsaved");
  }

  private void setClip(SoundClip next, boolean pushUndo) {
    player.stop();
    pausedAt = -1;
    if (pushUndo) {
      dirty.set(true);
      history.record(clip);
    }
    clip = next;
    selection = null;
    showView(viewStart, viewSpan);
  }

  private void undo() {
    if (!history.canUndo()) {
      return;
    }
    player.stop();
    pausedAt = -1;
    dirty.set(true);
    clip = history.undo(clip);
    selection = null;
    showView(viewStart, viewSpan);
  }

  private void redo() {
    if (!history.canRedo()) {
      return;
    }
    player.stop();
    pausedAt = -1;
    dirty.set(true);
    clip = history.redo(clip);
    selection = null;
    showView(viewStart, viewSpan);
  }

  /** Whether a sound is playing (tests). */
  boolean playing() {
    return player.isPlaying();
  }

  /** Where a paused sound goes on, or -1 (tests). */
  int pausedAt() {
    return pausedAt;
  }

  private void play() {
    int from = pausedAt;
    pausedAt = -1;
    boolean started = player.play(clip, selection, loopPlayback.isSelected(), from, () ->
        Platform.runLater(() -> {
          if (!player.isPlaying()) {
            status.setText("");
            redraw();
          }
        }));
    if (started) {
      liveWave.start();
      updateTransport();
    } else {
      status.setText(I18n.t("soundeditor.noaudio"));
    }
  }

  /**
   * The device list: looked up in the background when the editor opens, and
   * again each time it opens (a headset may have been plugged in).
   */
  private void deviceChooser() {
    device.setTooltip(new Tooltip(I18n.t("soundeditor.device.tooltip")));
    device.setConverter(new javafx.util.StringConverter<>() {
      @Override
      public String toString(MicrophoneRecorder.Device d) {
        return d == null ? "" : d.name();
      }

      @Override
      public MicrophoneRecorder.Device fromString(String text) {
        return null;
      }
    });
    device.setMaxWidth(220);
    showDevices(List.of(), Prefs.soundInputDevice());
    Thread lookup = new Thread(() -> {
      List<MicrophoneRecorder.Device> found = MicrophoneRecorder.devices(RECORD_RATE);
      Platform.runLater(() -> showDevices(found, Prefs.soundInputDevice()));
    }, "scratch4j-sound-devices");
    lookup.setDaemon(true);
    lookup.start();
    device.setOnShowing(e ->
        showDevices(MicrophoneRecorder.devices(RECORD_RATE), selectedDevice()));
    device.valueProperty().addListener((o, old, d) -> {
      if (d != null && !fillingDevices) Prefs.soundInputDevice(d.id());
    });
  }

  /** Lists the devices and selects the one with this id, or the default when it is gone. */
  private void showDevices(List<MicrophoneRecorder.Device> found, String id) {
    List<MicrophoneRecorder.Device> items = new ArrayList<>();
    items.add(DEFAULT_DEVICE);
    items.addAll(found);
    fillingDevices = true;
    try {
      device.getItems().setAll(items);
      device.setValue(items.stream().filter(d -> d.id().equals(id)).findFirst()
          .orElse(DEFAULT_DEVICE));
    } finally {
      fillingDevices = false;
    }
  }

  /** The chosen device's id; "" for the default microphone. */
  private String selectedDevice() {
    MicrophoneRecorder.Device d = device.getValue();
    return d == null ? "" : d.id();
  }

  private void record() {
    if (recorder.isRecording()) {
      SoundClip recorded = recorder.stop();
      recordingStopped();
      if (recorded != null) {
        setClip(recorded, true);
        showView(0, timelineLength());
      }
      return;
    }
    player.stop();
    pausedAt = -1;
    String id = selectedDevice();
    boolean started = recorder.start(RECORD_RATE, id.isEmpty() ? null : id);
    if (!started) {
      status.setText(I18n.t("soundeditor.nomicrophone"));
      return;
    }
    selection = null;
    liveWave.start();
    redraw();
  }

  private void recordingStopped() {
    liveWave.stop();
    redraw();
  }

  // --- undo steps kept with the file -----------------------------------------

  private static final String HISTORY = "sound";
  /** The clips as kept in the editor history, so a save encodes only new ones. */
  private final java.util.Map<SoundClip, byte[]> encoded = new java.util.IdentityHashMap<>();

  /** The undo and redo steps kept when the sound was last saved (if it is unchanged since). */
  private void loadHistory() {
    var steps = EditorHistory.read(projectRoot, file, HISTORY);
    try {
      List<SoundClip> undo = new ArrayList<>();
      List<SoundClip> redo = new ArrayList<>();
      for (byte[] step : steps.undo()) undo.add(clipOf(step));
      for (byte[] step : steps.redo()) redo.add(clipOf(step));
      history.load(undo, redo);
    } catch (IOException | RuntimeException e) {
      history.clear();
    }
  }

  private void saveHistory(Path target) {
    try {
      java.util.Map<SoundClip, byte[]> known = new java.util.IdentityHashMap<>();
      List<byte[]> undo = new ArrayList<>();
      List<byte[]> redo = new ArrayList<>();
      for (SoundClip step : history.undoSteps()) undo.add(bytesOf(step, known));
      for (SoundClip step : history.redoSteps()) redo.add(bytesOf(step, known));

      encoded.clear();
      encoded.putAll(known);
      EditorHistory.write(projectRoot, target, HISTORY, new EditorHistory.Steps(undo, redo, null));
    } catch (IOException | RuntimeException e) {
      status.setText(I18n.t("history.notkept", e.getMessage()));
    }
  }

  private byte[] bytesOf(SoundClip step, java.util.Map<SoundClip, byte[]> known) {
    byte[] bytes = encoded.get(step);
    if (bytes == null) {
      // the samples exactly as edited, not as 16-bit WAV
      int channels = step.channelCount();
      int samples = step.sampleCount();
      long size = 12 + 4L * channels * samples;
      if (size > Integer.MAX_VALUE) throw new IllegalStateException("sound too long");
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) size);
      buffer.putFloat(step.sampleRate()).putInt(channels).putInt(samples);
      for (int c = 0; c < channels; c++) {
        buffer.asFloatBuffer().put(step.channel(c));
        buffer.position(buffer.position() + 4 * samples);
      }
      bytes = buffer.array();
    }
    known.put(step, bytes);
    return bytes;
  }

  private SoundClip clipOf(byte[] bytes) throws IOException {
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
    float rate = buffer.getFloat();
    int channels = buffer.getInt();
    int samples = buffer.getInt();
    if (channels < 1 || samples < 0 || bytes.length != 12 + 4L * channels * samples) {
      throw new IOException("not a sound step");
    }
    float[][] data = new float[channels][samples];
    for (int c = 0; c < channels; c++) {
      buffer.asFloatBuffer().get(data[c]);
      buffer.position(buffer.position() + 4 * samples);
    }
    SoundClip step = new SoundClip(data, rate);
    encoded.put(step, bytes);
    return step;
  }

  @Override
  public void save() {
    try {
      Path target = file.toString().toLowerCase().endsWith(".wav")
          ? file
          : file.resolveSibling(
              file.getFileName().toString().replaceAll("(?i)\\.[a-z0-9]+$", "") + ".wav");
      Path temp = java.nio.file.Files.createTempFile(target.toAbsolutePath().getParent(),
          ".sound", ".wav");
      try {
        SoundIO.writeWav(clip, temp);
        LocalHistory.snapshot(projectRoot, target);
        AtomicFiles.replace(temp, target);
      } finally {
        java.nio.file.Files.deleteIfExists(temp);
      }
      dirty.set(false);
      boolean converted = !target.equals(file);
      status.setText(converted ? I18n.t("soundeditor.mp3.saved") : I18n.t("imageeditor.save"));
      saveHistory(target);
    } catch (IOException e) {
      status.setText("Could not save: " + e.getMessage());
    }
  }

  // --- waveform ------------------------------------------------------------

  private void redraw() {
    updateTransport();
    updateActions();
    var g = wave.getGraphicsContext2D();
    boolean dark = Theme.isDark();
    g.setFill(Color.web(dark ? "#0d1117" : "#ffffff"));
    g.fillRect(0, 0, wave.getWidth(), wave.getHeight());

    int width = (int) wave.getWidth();
    int height = (int) wave.getHeight() - RULER;
    if (recorder.isRecording()) {
      drawRecording(width, height, dark);
      return;
    }
    int samples = clip.sampleCount();
    int channelCount = Math.max(1, clip.channelCount());
    int channelHeight = height / channelCount;
    g.setStroke(Color.web(dark ? "#30363d" : "#e2e6ef"));
    for (int ch = 0; ch < channelCount; ch++) {
      double mid = ch * channelHeight + channelHeight / 2.0 + 0.5;
      g.strokeLine(0, mid, width, mid);
    }
    drawRuler(width, height, viewStart, viewSpan, dark);
    // past the end of the sound
    double soundEnd = Math.max(0, xAt(clip.duration()));
    if (soundEnd < width) {
      g.setFill(Color.web("#8c959f", dark ? 0.12 : 0.1));
      g.fillRect(soundEnd, 0, width - soundEnd, height);
      g.setStroke(Color.web(dark ? "#7d8590" : "#8c959f"));
      g.strokeLine(Math.floor(soundEnd) + 0.5, 0, Math.floor(soundEnd) + 0.5, height);
    }
    if (samples == 0) {
      g.setFill(Color.web(dark ? "#7d8590" : "#8b949e"));
      g.fillText(I18n.t("soundeditor.empty"), 12, height / 2.0 - 8);
      status.setText(I18n.t("soundeditor.empty"));
      return;
    }
    g.setFill(Color.web("#cf63cf"));
    for (int ch = 0; ch < channelCount; ch++) {
      int base = ch * channelHeight;
      float[] channel = clip.channel(ch);
      double perPixel = viewSpan / width * clip.sampleRate();
      double first = viewStart * clip.sampleRate();
      for (int x = 0; x < width; x++) {
        int start = (int) Math.floor(first + x * perPixel);
        int end = Math.min(samples, (int) Math.floor(first + (x + 1) * perPixel));
        if (start >= samples) break;
        float min = 0;
        float max = 0;
        for (int i = start; i < Math.max(start + 1, end); i += Math.max(1, (end - start) / 50)) {
          min = Math.min(min, channel[i]);
          max = Math.max(max, channel[i]);
        }
        int y0 = base + channelHeight / 2 - (int) (max * channelHeight / 2);
        int y1 = base + channelHeight / 2 - (int) (min * channelHeight / 2);
        g.fillRect(x, y0, 1, Math.max(1, y1 - y0));
      }
    }
    if (selection != null) {
      g.setFill(javafx.scene.paint.Color.rgb(133, 92, 214, 0.25));
      double x0 = xAt(selection.start() / clip.sampleRate());
      double x1 = xAt((selection.end() + 1) / clip.sampleRate());
      g.fillRect(x0, 0, Math.max(1, x1 - x0), height + RULER);
    }
    // the playhead; dashed where a paused sound will go on
    int playing = player.position();
    if (playing < 0) playing = pausedAt;
    if (playing >= 0) {
      double x = Math.floor(xAt(playing / clip.sampleRate())) + 0.5;
      g.setStroke(Color.web(dark ? "#e6edf3" : "#1f2328"));
      g.setLineWidth(2);
      g.setLineDashes(player.isPlaying() ? null : new double[] {4, 4});
      g.strokeLine(x, 0, x, height + RULER);
      g.setLineDashes((double[]) null);
      g.setLineWidth(1);
    }
    String info = String.format(java.util.Locale.ROOT, "%.2f s  %d Hz  %d ch",
        clip.duration(), (int) clip.sampleRate(), clip.channelCount());
    if (selection != null) {
      info = I18n.t("soundeditor.selection",
          String.format(java.util.Locale.ROOT, "%.2f", selection.start() / clip.sampleRate()),
          String.format(java.util.Locale.ROOT, "%.2f", (selection.end() + 1) / clip.sampleRate()),
          String.format(java.util.Locale.ROOT, "%.2f", selection.length() / clip.sampleRate()))
          + "    " + info;
    }
    status.setText(info);
  }

  /**
   * The sound coming in: its loudness so far, growing from the left over a
   * ten-second view that squeezes together once the recording is longer.
   */
  private void drawRecording(int width, int height, boolean dark) {
    var g = wave.getGraphicsContext2D();
    float[] peaks = recorder.peaks();
    double seconds = peaks.length / (double) MicrophoneRecorder.PEAKS_PER_SECOND;
    double span = Math.max(10, seconds);
    drawRuler(width, height, 0, span, dark);
    double mid = height / 2.0;
    g.setStroke(Color.web(dark ? "#30363d" : "#e2e6ef"));
    g.strokeLine(0, mid + 0.5, width, mid + 0.5);
    g.setFill(Color.web("#e5534b"));
    double perPeak = width / (span * MicrophoneRecorder.PEAKS_PER_SECOND);
    for (int x = 0; x < width; x++) {
      int from = (int) (x / perPeak);
      int to = Math.min(peaks.length, (int) ((x + 1) / perPeak));
      if (from >= peaks.length) break;
      float peak = 0;
      for (int i = from; i < Math.max(from + 1, to); i++) {
        peak = Math.max(peak, peaks[i]);
      }
      double h = Math.max(1, peak * height);
      g.fillRect(x, mid - h / 2, 1, h);
    }
    // where the sound comes in
    double now = Math.min(width - 1, seconds / span * width);
    g.fillRect(now, 0, 2, height);
    status.setText(I18n.t("soundeditor.recording",
        String.format(java.util.Locale.ROOT, "%.1f", seconds)));
  }

  /**
   * Seconds ticks under the waveform from {@code start} over {@code seconds};
   * labels at the finest step that leaves them room.
   */
  private void drawRuler(int width, int top, double start, double seconds, boolean dark) {
    var g = wave.getGraphicsContext2D();
    g.setFill(Color.web(dark ? "#161b22" : "#f6f8fa"));
    g.fillRect(0, top, width, RULER);
    Color ink = Color.web(dark ? "#7d8590" : "#57606a");
    g.setStroke(ink);
    g.strokeLine(0, top + 0.5, width, top + 0.5);
    if (seconds <= 0) return;
    double step = rulerStep(seconds, width);
    g.setFill(ink);
    g.setFont(javafx.scene.text.Font.font(10));
    int minor = 5;
    double tick = step / minor;
    for (long i = (long) Math.ceil(start / tick - 1e-9); i * tick <= start + seconds; i++) {
      double t = i * tick;
      double x = Math.floor((t - start) / seconds * (width - 1)) + 0.5;
      boolean major = i % minor == 0;
      g.strokeLine(x, top, x, top + (major ? 7 : 3));
      if (major) {
        String label = rulerLabel(t, step);
        // the last label stays inside the canvas
        double labelX = Math.min(x + 2, width - 7 * label.length());
        g.fillText(label, labelX, top + RULER - 4);
      }
    }
  }

  /** The labelled step in seconds: 1, 2 or 5 times a power of ten, at least 70 px apart. */
  static double rulerStep(double seconds, double width) {
    double minimum = seconds * 70 / Math.max(1, width);
    double step = Math.pow(10, Math.floor(Math.log10(Math.max(minimum, 1e-3))));
    for (double factor : new double[] {1, 2, 5, 10}) {
      if (step * factor >= minimum) return step * factor;
    }
    return step * 10;
  }

  /** "0", "1.5 s", "0.25 s" or "1:30" with as many decimals as the step needs. */
  static String rulerLabel(double t, double step) {
    if (t >= 60 && step >= 1) {
      long whole = Math.round(t);
      return String.format(java.util.Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
    }
    int decimals = step >= 1 ? 0 : (int) Math.ceil(-Math.log10(step) - 1e-9);
    return String.format(java.util.Locale.ROOT, "%." + decimals + "f s", t);
  }
}
