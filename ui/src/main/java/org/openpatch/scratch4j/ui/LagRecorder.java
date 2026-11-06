package org.openpatch.scratch4j.ui;

import javafx.application.Platform;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes down when the window freezes: if the FX thread does not answer for
 * {@link #STALL_MS} or more, it samples what that thread is doing until it
 * answers again, then appends the freeze to {@code lag.log} — how long, how
 * much of it the garbage collector took, and the stacks it was seen in. The
 * log is for finding the lags on slow school computers that a fast one never shows.
 */
final class LagRecorder {

  static final long STALL_MS = 300;
  private static final long TICK_MS = 50;
  private static final long MAX_LOG_BYTES = 1_000_000;
  private static final int FRAMES = 30;

  private static volatile boolean started;

  private final Path log;
  private final Thread fx;
  private volatile long answered = System.nanoTime();
  private volatile boolean waiting;

  private LagRecorder(Path log, Thread fx) {
    this.log = log;
    this.fx = fx;
  }

  /** The log next to portable settings, else in ~/.scratch4j. */
  static Path logFile() {
    Path portable = Prefs.portableDir();
    Path dir = portable != null ? portable
        : Path.of(System.getProperty("user.home"), ".scratch4j");
    return dir.resolve("lag.log");
  }

  /** Starts watching the FX thread (call on it); once per app. */
  static void start() {
    if (started || Boolean.getBoolean("scratch4j.noLagLog")) return;
    started = true;
    watch(logFile());
  }

  /** Watches the FX thread (call on it) into {@code log}; interrupt the thread to stop. */
  static Thread watch(Path log) {
    LagRecorder recorder = new LagRecorder(log, Thread.currentThread());
    Thread watcher = new Thread(recorder::watch, "lag-recorder");
    watcher.setDaemon(true);
    watcher.setPriority(Thread.MAX_PRIORITY);
    watcher.start();
    return watcher;
  }

  private void watch() {
    while (true) {
      try {
        if (!waiting) {
          waiting = true;
          Platform.runLater(() -> {
            answered = System.nanoTime();
            waiting = false;
          });
        }
        // before the sleep: a collector's pause stops this thread too
        long gc = gcMillis();
        Thread.sleep(TICK_MS);
        long silent = (System.nanoTime() - answered) / 1_000_000;
        if (waiting && silent >= STALL_MS) record(answered, gc);
      } catch (InterruptedException e) {
        return;
      } catch (RuntimeException e) {
        // the recorder must never take the app down
      }
    }
  }

  /** Samples the FX thread until it answers again, then writes the freeze. */
  private void record(long since, long gcBefore) throws InterruptedException {
    Map<String, Integer> stacks = new LinkedHashMap<>();
    int samples = 0;
    while (waiting && answered == since) {
      String stack = stackOf(fx.getStackTrace());
      stacks.merge(stack, 1, Integer::sum);
      samples++;
      Thread.sleep(TICK_MS);
    }
    long millis = (System.nanoTime() - since) / 1_000_000;
    long gc = gcMillis() - gcBefore;
    StringBuilder out = new StringBuilder();
    out.append("=== ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        .append("  frozen ").append(millis).append(" ms")
        .append(" (garbage collector ").append(gc).append(" ms)")
        .append("  cpus ").append(Runtime.getRuntime().availableProcessors())
        .append("  heap ").append(usedMb()).append('/')
        .append(Runtime.getRuntime().maxMemory() >> 20).append(" MB\n");
    int sampled = samples;
    stacks.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .limit(5)
        .forEach(e -> out.append("--- seen in ").append(e.getValue()).append('/')
            .append(sampled).append(" samples\n").append(e.getKey()));
    out.append('\n');
    write(out.toString());
  }

  private static String stackOf(StackTraceElement[] frames) {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < Math.min(FRAMES, frames.length); i++) {
      s.append("    at ").append(frames[i]).append('\n');
    }
    return s.toString();
  }

  private void write(String entry) {
    try {
      Files.createDirectories(log.getParent());
      if (Files.isRegularFile(log) && Files.size(log) > MAX_LOG_BYTES) {
        Files.move(log, log.resolveSibling("lag.old.log"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      Files.writeString(log, entry, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // no log then
    }
  }

  private static long gcMillis() {
    long total = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      total += Math.max(0, gc.getCollectionTime());
    }
    return total;
  }

  private static long usedMb() {
    Runtime r = Runtime.getRuntime();
    return (r.totalMemory() - r.freeMemory()) >> 20;
  }
}
