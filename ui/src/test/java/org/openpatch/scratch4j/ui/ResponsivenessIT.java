package org.openpatch.scratch4j.ui;

import javafx.application.Platform;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.io.LocalHistory;
import org.openpatch.scratch4j.runner.RunHandle;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openpatch.scratch4j.ui.LayoutIT.onFx;
import static org.openpatch.scratch4j.ui.LayoutIT.startFx;

@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class ResponsivenessIT {
  @TempDir Path root;

  @BeforeEach
  void setUp() {
    System.setProperty("scratch4j.settingsDir", root.resolve("settings").toString());
    I18n.set(I18n.Language.EN);
    startFx();
  }

  @Test
  void consoleYieldsToInputAndRetainsTheNewestOutputWithoutUndoHistory() throws Exception {
    AtomicReference<ConsoleView> view = new AtomicReference<>();
    CompletableFuture<Integer> input = new CompletableFuture<>();
    onFx(() -> {
      ConsoleView console = new ConsoleView((f, l) -> { });
      view.set(console);
      for (int i = 0; i < 20_000; i++) console.out("debug position " + i);
      console.err("at Player.run(Player.java:12)");
      console.out("last output");
      Platform.runLater(() -> input.complete(console.content().length()));
    });
    // Input gets a turn before the complete backlog has been rendered.
    assertThat(input.get(5, TimeUnit.SECONDS)).isBetween(1, 8192);
    awaitFx(() -> view.get().content().endsWith("last output\n"));
    onFx(() -> {
      ConsoleView console = view.get();
      assertThat(console.content()).contains(I18n.t("console.omitted"), "Player.java:12")
          .doesNotContain("debug position 0\n");
      assertThat(console.content().length()).isLessThanOrEqualTo(400_000);
      StyleClassedTextArea text = (StyleClassedTextArea) console.lookup(".console-text");
      int link = console.content().indexOf("Player.java:12");
      assertThat(text.getStyleOfChar(link)).contains("err", "link");
      assertThat(text.getUndoManager().isUndoAvailable()).isFalse();
      console.clear();
      assertThat(text.getUndoManager().isUndoAvailable()).isFalse();
    });
  }

  @Test
  void clearingTheConsoleDiscardsOutputAlreadyQueued() throws Exception {
    AtomicReference<ConsoleView> view = new AtomicReference<>();
    onFx(() -> {
      ConsoleView console = new ConsoleView((f, l) -> { });
      view.set(console);
      console.out("old run");
      console.clear();
      console.out("new run");
    });
    awaitFx(() -> view.get().content().endsWith("new run\n"));
    onFx(() -> assertThat(view.get().content()).isEqualTo("new run\n"));
  }

  @Test
  void aSingleEnormousOutputLineIsBoundedAndLaterLinesStillArrive() throws Exception {
    AtomicReference<ConsoleView> view = new AtomicReference<>();
    onFx(() -> {
      ConsoleView console = new ConsoleView((f, l) -> { });
      view.set(console);
      console.out("x".repeat(800_000));
      console.out("last output");
    });
    awaitFx(() -> view.get().content().endsWith("last output\n"));
    onFx(() -> {
      assertThat(view.get().content()).contains(I18n.t("console.omitted"));
      assertThat(view.get().content().length()).isLessThan(17_000);
      view.get().clear();
    });
  }

  @Test
  void aFailedSaveKeepsTheTabOpenAndCanBeRetried() throws Exception {
    Path file = root.resolve("Cat.java");
    Files.writeString(file, "original");
    AtomicBoolean failing = new AtomicBoolean(true);
    CountDownLatch failed = new CountDownLatch(1);
    AtomicReference<EditorTabs> tabs = new AtomicReference<>();
    onFx(() -> {
      EditorTabs editor = new EditorTabs(null, () -> root, (base, target, text) -> {
        if (failing.get()) throw new java.io.IOException("Disk unavailable");
        LocalHistory.writeString(base, target, text);
      });
      tabs.set(editor);
      editor.setOnSaveFailed(error -> failed.countDown());
      editor.open(file);
      editor.activeEditor().area().replaceText("latest");
      editor.autosaveAll();
    });
    assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
    onFx(() -> {
      tabs.get().closeSelected();
      assertThat(tabs.get().editorFor(file)).isNotNull();
      assertThat(tabs.get().activeEditor().hasUnsavedChanges()).isTrue();
      failing.set(false);
      tabs.get().saveAll();
      assertThat(tabs.get().activeEditor().hasUnsavedChanges()).isFalse();
      tabs.get().clear();
    });
    assertThat(Files.readString(file)).isEqualTo("latest");
  }

  @Test
  void delayedAutosaveDoesNotMarkNewerEditsSaved() throws Exception {
    Path file = root.resolve("Cat.java");
    Files.writeString(file, "original");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean offFx = new AtomicBoolean();
    AtomicReference<EditorTabs> tabs = new AtomicReference<>();
    try {
      onFx(() -> {
        EditorTabs editor = new EditorTabs(null, () -> root, (base, target, text) -> {
          offFx.set(!Platform.isFxApplicationThread());
          if (text.equals("first")) {
            started.countDown();
            await(release);
          }
          LocalHistory.writeString(base, target, text);
        });
        tabs.set(editor);
        editor.open(file);
        editor.activeEditor().area().replaceText("first");
        editor.autosaveAll();
      });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      onFx(() -> tabs.get().activeEditor().area().replaceText("second"));
      release.countDown();
      awaitFx(() -> {
        try { return Files.readString(file).equals("first")
            && tabs.get().activeEditor().dirtyProperty().get(); }
        catch (Exception e) { return false; }
      });
      assertThat(offFx.get()).isTrue();
      onFx(() -> tabs.get().autosaveAll());
      awaitFx(() -> !tabs.get().activeEditor().hasUnsavedChanges());
      assertThat(Files.readString(file)).isEqualTo("second");
    } finally {
      release.countDown();
      onFx(() -> { if (tabs.get() != null) tabs.get().clear(); });
    }
  }

  @Test
  void explicitSaveSupersedesQueuedSnapshotsAndTheirCallbacks() throws Exception {
    Path file = root.resolve("Cat.java");
    Files.writeString(file, "original");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<EditorTabs> tabs = new AtomicReference<>();
    CompletableFuture<Void> saved = new CompletableFuture<>();
    List<String> writes = new java.util.concurrent.CopyOnWriteArrayList<>();
    try {
      onFx(() -> {
        EditorTabs editor = new EditorTabs(null, () -> root, (base, target, text) -> {
          if (text.equals("first")) { started.countDown(); await(release); }
          writes.add(text);
          LocalHistory.writeString(base, target, text);
        });
        tabs.set(editor);
        editor.open(file);
        editor.activeEditor().area().replaceText("first");
        editor.autosaveAll();
      });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      onFx(() -> {
        tabs.get().activeEditor().area().replaceText("queued");
        tabs.get().autosaveAll();
        tabs.get().activeEditor().area().replaceText("latest");
      });
      Platform.runLater(() -> {
        try { tabs.get().saveAll(); saved.complete(null); }
        catch (Throwable e) { saved.completeExceptionally(e); }
      });
      release.countDown();
      saved.get(5, TimeUnit.SECONDS);
      // A later background save acts as a barrier for earlier jobs/callbacks.
      onFx(() -> {
        tabs.get().activeEditor().area().replaceText("final");
        tabs.get().autosaveAll();
      });
      awaitFx(() -> !tabs.get().activeEditor().hasUnsavedChanges());
      assertThat(Files.readString(file)).isEqualTo("final");
      assertThat(writes.getLast()).isEqualTo("final");
      onFx(() -> assertThat(tabs.get().activeEditor().dirtyProperty().get()).isFalse());
    } finally {
      release.countDown();
      onFx(() -> { if (tabs.get() != null) tabs.get().clear(); });
    }
  }

  @Test
  void undoToSavedTextWhileAutosaveIsRunningStillRestoresTheDisk() throws Exception {
    Path file = root.resolve("Cat.java");
    Files.writeString(file, "original");
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch restored = new CountDownLatch(1);
    AtomicReference<EditorTabs> tabs = new AtomicReference<>();
    try {
      onFx(() -> {
        EditorTabs editor = new EditorTabs(null, () -> root, (base, target, text) -> {
          if (text.equals("first")) { started.countDown(); await(release); }
          LocalHistory.writeString(base, target, text);
          if (text.equals("original")) restored.countDown();
        });
        tabs.set(editor);
        editor.open(file);
        editor.activeEditor().area().replaceText("first");
        editor.autosaveAll();
      });
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      onFx(() -> tabs.get().activeEditor().undo());
      // Let the edit debounce run while the old snapshot is still in flight.
      Thread.sleep(100);
      release.countDown();
      assertThat(restored.await(5, TimeUnit.SECONDS)).isTrue();
      awaitFx(() -> !tabs.get().activeEditor().hasUnsavedChanges());
      assertThat(Files.readString(file)).isEqualTo("original");
    } finally {
      release.countDown();
      onFx(() -> { if (tabs.get() != null) tabs.get().clear(); });
    }
  }

  @Test
  void regionMarkersFollowTheLatestTextAndDisappearWhenItsBlocksAreRemoved() throws Exception {
    AtomicReference<CodeEditor> editor = new AtomicReference<>();
    String source = "class Cat extends Stage {\n// begin stage designer\nSprite sprite;\n"
        + "// end stage designer\nCat() {\n// begin stage designer\nhelper();\n"
        + "// end stage designer\n}\n}";
    onFx(() -> editor.set(new CodeEditor(root.resolve("Cat.java"), source, null, () -> root)));
    awaitFx(() -> "region-own".equals(editor.get().regionStyle(7)));
    onFx(() -> {
      editor.get().area().replaceText("\n\n" + source);
      editor.get().area().replaceText("class Cat {}\n");
    });
    awaitFx(() -> editor.get().regionStyle(7) == null && editor.get().regionStyle(9) == null);
    onFx(() -> editor.get().close());
  }

  @Test
  void stoppingAnUnresponsiveProcessLeavesTheFxThreadAvailable() throws Exception {
    CountDownLatch stopping = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Process process = new Process() {
      private volatile boolean alive = true;
      public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
      public InputStream getInputStream() { return InputStream.nullInputStream(); }
      public InputStream getErrorStream() { return InputStream.nullInputStream(); }
      public int waitFor() { return 0; }
      public int exitValue() { return 0; }
      public void destroy() { }
      public boolean isAlive() { return alive; }
      public Stream<ProcessHandle> descendants() { return Stream.empty(); }
      public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        stopping.countDown();
        try { return release.await(5, TimeUnit.SECONDS); }
        finally { alive = false; }
      }
    };
    CompletableFuture<Void> uiReturned = new CompletableFuture<>();
    try {
      Platform.runLater(() -> {
        try {
          var constructor = RunHandle.class.getDeclaredConstructor(Process.class, CompletableFuture.class);
          constructor.setAccessible(true);
          RunHandle handle = constructor.newInstance(process, new CompletableFuture<Integer>());
          StudioApp app = new StudioApp();
          var field = StudioApp.class.getDeclaredField("currentRun");
          field.setAccessible(true);
          @SuppressWarnings("unchecked")
          var current = (AtomicReference<RunHandle>) field.get(app);
          current.set(handle);
          var stop = StudioApp.class.getDeclaredMethod("stopProgram");
          stop.setAccessible(true);
          stop.invoke(app);
          Platform.runLater(() -> uiReturned.complete(null));
        } catch (Throwable e) { uiReturned.completeExceptionally(e); }
      });
      assertThat(stopping.await(5, TimeUnit.SECONDS)).isTrue();
      uiReturned.get(2, TimeUnit.SECONDS); // process termination is still blocked
    } finally {
      release.countDown();
    }
  }

  private static void await(CountDownLatch latch) throws java.io.IOException {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Test writer timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new java.io.IOException(e);
    }
  }

  private static void awaitFx(BooleanSupplier condition) throws Exception {
    AtomicBoolean complete = new AtomicBoolean();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      onFx(() -> complete.set(condition.getAsBoolean()));
      if (complete.get()) return;
      Thread.sleep(20);
    }
    assertThat(complete.get()).as("background work completed").isTrue();
  }
}
