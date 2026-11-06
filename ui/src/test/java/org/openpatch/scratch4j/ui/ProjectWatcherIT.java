package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.io.AtomicFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A save (a temporary file moved over the code) does not read the whole
 * project into the Files tree again; a file that really came does
 * (needs a display — {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class ProjectWatcherIT {

  @TempDir
  Path tmp;

  @Test
  void aSaveLeavesTheTreeAndANewFileShowsUp() throws Exception {
    LayoutIT.startFx();
    Path cat = tmp.resolve("Cat.java");
    Files.writeString(cat, "class Cat {}");
    AtomicReference<FileTreeView> tree = new AtomicReference<>();
    AtomicReference<ProjectWatcher> watcher = new AtomicReference<>();
    List<Set<Path>> reports = new ArrayList<>();
    AtomicReference<Object> rootBefore = new AtomicReference<>();
    LayoutIT.onFx(() -> {
      FileTreeView view = new FileTreeView();
      view.setRoot(tmp);
      tree.set(view);
      watcher.set(ProjectWatcher.start(tmp, paths -> {
        reports.add(paths);
        view.reloadIfChanged(paths);
      }));
    });
    // the tree reads the folder in the background
    waitFor(() -> !tree.get().loading() && tree.get().getRoot() != null);
    LayoutIT.onFx(() -> rootBefore.set(tree.get().getRoot()));
    try {
      AtomicFiles.writeString(cat, "class Cat { int x; }");
      waitFor(() -> !reports.isEmpty());
      LayoutIT.onFx(() -> assertThat(tree.get().getRoot())
          .as("the tree after a save").isSameAs(rootBefore.get()));

      Files.writeString(tmp.resolve("Dog.java"), "class Dog {}");
      int seen = reports.size();
      waitFor(() -> reports.size() > seen && !tree.get().loading());
      LayoutIT.onFx(() -> assertThat(tree.get().getRoot().getChildren())
          .extracting(item -> item.getValue().getFileName().toString())
          .contains("Cat.java", "Dog.java"));
    } finally {
      LayoutIT.onFx(() -> watcher.get().close());
    }
  }

  private static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
    for (int i = 0; i < 50; i++) {
      boolean[] ok = {false};
      LayoutIT.onFx(() -> ok[0] = done.getAsBoolean());
      if (ok[0]) return;
      Thread.sleep(100);
    }
    throw new AssertionError("no report from the watcher");
  }
}
