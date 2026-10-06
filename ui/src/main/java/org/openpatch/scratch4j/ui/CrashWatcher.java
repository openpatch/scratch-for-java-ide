package org.openpatch.scratch4j.ui;

import org.openpatch.scratch4j.core.lint.DiagnosticsExplanations;
import org.openpatch.scratch4j.core.lint.RuntimeErrors;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches a run's stderr for stack traces and reports each different crash
 * once, explained ({@link RuntimeErrors}). A trace counts as complete at the
 * next unrelated line, when the program ends, or after a short pause in the
 * output (a program that keeps running prints nothing more).
 */
final class CrashWatcher {

  private static final ScheduledExecutorService TIMER =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "crash-watcher");
        t.setDaemon(true);
        return t;
      });

  private final ScratchProject project;
  private final DiagnosticsExplanations.Language language;
  private final Consumer<RuntimeErrors.Explanation> onCrash;
  private final RuntimeErrors.Collector collector = new RuntimeErrors.Collector();
  private final Set<String> reported = new HashSet<>();
  private ScheduledFuture<?> pending;

  CrashWatcher(ScratchProject project, DiagnosticsExplanations.Language language,
      Consumer<RuntimeErrors.Explanation> onCrash) {
    this.project = project;
    this.language = language;
    this.onCrash = onCrash;
  }

  synchronized void feed(String line) {
    collector.feed(line).ifPresent(this::report);
    if (pending != null) {
      pending.cancel(false);
    }
    pending = TIMER.schedule(this::flush, 400, TimeUnit.MILLISECONDS);
  }

  synchronized void flush() {
    collector.flush().ifPresent(this::report);
  }

  private void report(RuntimeErrors.Crash crash) {
    RuntimeErrors.Explanation e = RuntimeErrors.explain(crash, this::projectFile, language);
    // a crash in run() repeats every frame: once is enough
    if (reported.add(crash.exception() + "|" + e.file() + "|" + e.line())) {
      onCrash.accept(e);
    }
  }

  private Path projectFile(String fileName) {
    try {
      Path file = project.sourceOf(fileName.replaceFirst("\\.java$", ""));
      return file != null && fileName.endsWith(".java") ? file : null;
    } catch (IOException e) {
      return null;
    }
  }
}
