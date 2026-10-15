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
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The sound editor: waveform view, play/loop selection, cut/copy/paste/
 * delete/trim, insert silence, fades, louder/softer, normalize, reverse,
 * faster/slower, undo/redo, microphone recording, WAV save. MP3 files open
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

  private final Deque<SoundClip> undoStack = new ArrayDeque<>();
  private final Deque<SoundClip> redoStack = new ArrayDeque<>();
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

    // the usual shortcuts anywhere in the editor (Ctrl is Cmd on macOS)
    addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
      if (EditorKeys.isEditorKey(e)
          && shortcut(e.getCode(), e.isShortcutDown(), e.isShiftDown())) {
        e.consume();
      }
    });

    wave.setFocusTraversable(true);
    wave.setOnMousePressed(e -> {
      wave.requestFocus(); // the shortcuts need the focus in the editor
      dragStartX = e.getX();
      selection = null;
      redraw();
    });
    wave.setOnMouseDragged(e -> {
      if (clip.sampleCount() == 0) {
        return;
      }
      int start = sampleAt(Math.min(dragStartX, e.getX()));
      int end = sampleAt(Math.max(dragStartX, e.getX()));
      selection = end > start ? new SoundEffects.Selection(start, end) : null;
      redraw();
    });
    wave.setOnMouseReleased(e -> dragStartX = -1);
    wave.setCursor(javafx.scene.Cursor.TEXT);

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

    VBox top = new VBox(transportBar(), effectsBar());
    setTop(top);
    setCenter(waveBox);
    BorderPane.setMargin(waveBox, new Insets(12));
    HBox bottom = new HBox(8, status);
    bottom.getStyleClass().add("editor-status");
    setBottom(bottom);
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
    int sample = (int) Math.round(x / wave.getWidth() * (clip.sampleCount() - 1));
    return Math.max(0, Math.min(clip.sampleCount() - 1, sample));
  }

  private Node transportBar() {
    Button play = new Button(I18n.t("soundeditor.play"), Icons.of("fth-play"));
    play.getStyleClass().add("success");
    play.setOnAction(e -> play());
    play.setTooltip(new Tooltip(I18n.t("soundeditor.play.tooltip")));
    Button stop = new Button(null, Icons.of("fth-square"));
    stop.setTooltip(new Tooltip(I18n.t("soundeditor.stop")));
    stop.setOnAction(e -> {
      player.stop();
      status.setText("");
    });
    loopPlayback.setTooltip(new Tooltip(I18n.t("soundeditor.loop.tooltip")));
    Button record = new Button(I18n.t("soundeditor.record"), Icons.of("fth-mic"));
    record.getStyleClass().add("danger");
    record.setOnAction(e -> {
      record();
      record.setText(recorder.isRecording() ? I18n.t("soundeditor.stoprecord")
          : I18n.t("soundeditor.record"));
    });
    Button save = new Button(I18n.t("soundeditor.save"), Icons.of("fth-save"));
    save.getStyleClass().add("accent");
    save.setOnAction(e -> save());
    save.setTooltip(new Tooltip(I18n.t("editor.save.tooltip")));
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    for (Button b : List.of(play, record, save)) {
      b.setMinWidth(Region.USE_PREF_SIZE);
    }
    HBox bar = new HBox(6, play, stop, loopPlayback, record,
        new Separator(javafx.geometry.Orientation.VERTICAL),
        Icons.button("fth-corner-up-left", I18n.t("soundeditor.undo"), this::undo),
        Icons.button("fth-corner-up-right", I18n.t("soundeditor.redo"), this::redo),
        spacer, save);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("tool-bar-row");
    return bar;
  }

  /** Edit and effect buttons in two labelled groups; they wrap instead of overflowing. */
  private Node effectsBar() {
    List<Button> buttons = effectButtons();
    FlowPane edit = new FlowPane(4, 4);
    edit.setPrefWrapLength(330);
    FlowPane effects = new FlowPane(4, 4);
    effects.setPrefWrapLength(430);
    for (int i = 0; i < buttons.size(); i++) {
      (i < 6 ? edit : effects).getChildren().add(buttons.get(i));
    }
    Label editLabel = new Label(I18n.t("soundeditor.group.edit"));
    editLabel.getStyleClass().add("field-label");
    Label effectsLabel = new Label(I18n.t("soundeditor.group.effects"));
    effectsLabel.getStyleClass().add("field-label");
    // the two groups sit side by side and stack when the tab is narrow
    FlowPane row = new FlowPane(20, 8, new VBox(4, editLabel, edit),
        new VBox(4, effectsLabel, effects));
    row.setMinWidth(0);
    row.getStyleClass().add("effects-row");
    return row;
  }

  private List<Button> effectButtons() {
    List<Button> buttons = new ArrayList<>();
    buttons.add(effect("soundeditor.cut", "fth-scissors", () -> withSelection(this::cut)));
    buttons.add(effect("soundeditor.copy", "fth-copy", () -> withSelection(this::copy)));
    buttons.add(effect("soundeditor.paste", "fth-clipboard", this::paste));
    buttons.add(effect("soundeditor.delete", "fth-trash-2", () -> withSelection(this::delete)));
    buttons.add(effect("soundeditor.trim", "fth-crop", () -> withSelection(sel ->
        setClip(SoundEffects.trim(clip, sel), true))));
    buttons.add(effect("soundeditor.silence", "fth-volume-x", () -> {
      int at = selection == null ? clip.sampleCount() : selection.start();
      setClip(SoundEffects.insertSilence(clip, at, 0.5), true);
    }));
    buttons.add(effect("soundeditor.fadein", "fth-trending-up", () ->
        setClip(SoundEffects.fadeIn(clip, selection), true)));
    buttons.add(effect("soundeditor.fadeout", "fth-trending-down", () ->
        setClip(SoundEffects.fadeOut(clip, selection), true)));
    buttons.add(effect("soundeditor.louder", "fth-volume-2", () ->
        setClip(SoundEffects.volume(clip, selection, 1.25), true)));
    buttons.add(effect("soundeditor.softer", "fth-volume-1", () ->
        setClip(SoundEffects.volume(clip, selection, 0.8), true)));
    buttons.add(effect("soundeditor.normalize", "fth-bar-chart-2", () ->
        setClip(SoundEffects.normalize(clip, selection), true)));
    buttons.add(effect("soundeditor.reverse", "fth-rewind", () ->
        setClip(SoundEffects.reverse(clip, selection), true)));
    buttons.add(effect("soundeditor.faster", "fth-fast-forward", () ->
        setClip(SoundEffects.speed(clip, 1.25), true)));
    buttons.add(effect("soundeditor.slower", "fth-clock", () ->
        setClip(SoundEffects.speed(clip, 0.8), true)));
    buttons.add(effect("soundeditor.echo", "fth-radio", () ->
        setClip(SoundEffects.echo(clip, selection), true)));
    buttons.add(effect("soundeditor.robot", "fth-cpu", () ->
        setClip(SoundEffects.robot(clip, selection), true)));
    return buttons;
  }

  private void cut(SoundEffects.Selection sel) {
    clipboard = SoundEffects.copy(clip, sel);
    setClip(SoundEffects.cut(clip, sel), true);
  }

  private void copy(SoundEffects.Selection sel) {
    clipboard = SoundEffects.copy(clip, sel);
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
   * Save, undo/redo, select all, copy/cut/paste; Space plays or stops, Delete
   * removes the selection, Escape stops and clears it. The keys that change
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
      case SPACE -> {
        if (player.isPlaying()) {
          player.stop();
          status.setText("");
        } else {
          play();
        }
      }
      case DELETE, BACK_SPACE -> {
        if (selection == null) return false;
        delete(selection);
      }
      case ESCAPE -> {
        player.stop();
        selection = null;
        redraw();
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /** One effect button; empty sounds have nothing to edit. */
  private Button effect(String key, String icon, Runnable action) {
    Button button = new Button(I18n.t(key), Icons.of(icon));
    button.getStyleClass().addAll("small", "effect-button");
    button.setOnAction(e -> {
      if (clip.sampleCount() > 0 || key.equals("soundeditor.paste")) {
        action.run();
      }
    });
    return button;
  }

  private interface SelectionAction {
    void run(SoundEffects.Selection selection);
  }

  private void withSelection(SelectionAction action) {
    if (selection == null) {
      status.setText(I18n.t("soundeditor.effect.all"));
      action.run(new SoundEffects.Selection(0, clip.sampleCount() - 1));
    } else {
      action.run(selection);
    }
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
    if (pushUndo) {
      dirty.set(true);
      undoStack.push(clip);
      if (undoStack.size() > 40) {
        undoStack.removeLast();
      }
      redoStack.clear();
    }
    clip = next;
    selection = null;
    redraw();
  }

  private void undo() {
    if (undoStack.isEmpty()) {
      return;
    }
    player.stop();
    dirty.set(true);
    redoStack.push(clip);
    clip = undoStack.pop();
    selection = null;
    redraw();
  }

  private void redo() {
    if (redoStack.isEmpty()) {
      return;
    }
    player.stop();
    dirty.set(true);
    undoStack.push(clip);
    clip = redoStack.pop();
    selection = null;
    redraw();
  }

  private void play() {
    boolean started = player.play(clip, selection, loopPlayback.isSelected(), () ->
        Platform.runLater(() -> {
          if (!player.isPlaying()) {
            status.setText("");
          }
        }));
    if (!started) {
      status.setText(I18n.t("soundeditor.noaudio"));
    }
  }

  private void record() {
    if (recorder.isRecording()) {
      SoundClip recorded = recorder.stop();
      if (recorded != null) {
        setClip(recorded, true);
      }
      return;
    }
    boolean started = recorder.start(44100);
    if (!started) {
      status.setText("no microphone");
    }
  }

  // --- undo steps kept with the file -----------------------------------------

  private static final String HISTORY = "sound";
  /** The clips as kept in the editor history, so a save encodes only new ones. */
  private final java.util.Map<SoundClip, byte[]> encoded = new java.util.IdentityHashMap<>();

  /** The undo and redo steps kept when the sound was last saved (if it is unchanged since). */
  private void loadHistory() {
    var steps = EditorHistory.read(projectRoot, file, HISTORY);
    try {
      for (byte[] step : steps.undo()) undoStack.addLast(clipOf(step));
      for (byte[] step : steps.redo()) redoStack.addLast(clipOf(step));
    } catch (IOException | RuntimeException e) {
      undoStack.clear();
      redoStack.clear();
    }
  }

  private void saveHistory(Path target) {
    try {
      java.util.Map<SoundClip, byte[]> known = new java.util.IdentityHashMap<>();
      List<byte[]> undo = new ArrayList<>();
      List<byte[]> redo = new ArrayList<>();
      for (SoundClip step : undoStack) undo.add(bytesOf(step, known));
      for (SoundClip step : redoStack) redo.add(bytesOf(step, known));
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
    var g = wave.getGraphicsContext2D();
    boolean dark = Theme.isDark();
    g.setFill(Color.web(dark ? "#0d1117" : "#ffffff"));
    g.fillRect(0, 0, wave.getWidth(), wave.getHeight());

    int width = (int) wave.getWidth();
    int height = (int) wave.getHeight();
    int samples = clip.sampleCount();
    int channelCount = Math.max(1, clip.channelCount());
    int channelHeight = height / channelCount;
    g.setStroke(Color.web(dark ? "#30363d" : "#e2e6ef"));
    for (int ch = 0; ch < channelCount; ch++) {
      double mid = ch * channelHeight + channelHeight / 2.0 + 0.5;
      g.strokeLine(0, mid, width, mid);
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
      for (int x = 0; x < width; x++) {
        int start = (int) ((long) samples * x / width);
        int end = (int) ((long) samples * (x + 1) / width);
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
      double x0 = (double) selection.start() / samples * width;
      double x1 = (double) (selection.end() + 1) / samples * width;
      g.fillRect(x0, 0, Math.max(1, x1 - x0), height);
    }
    status.setText(String.format(java.util.Locale.ROOT, "%.2f s  %d Hz  %d ch",
        clip.duration(), (int) clip.sampleRate(), clip.channelCount()));
  }
}
