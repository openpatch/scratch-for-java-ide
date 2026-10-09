package org.openpatch.scratch4j.runner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/** A running (or already finished) student program. */
public final class RunHandle {

  private final Process process;
  private final CompletableFuture<Integer> exit;

  RunHandle(Process process, CompletableFuture<Integer> exit) {
    this.process = process;
    this.exit = exit;
  }

  public boolean isRunning() {
    return process.isAlive();
  }

  /** The program's pid, or -1 if it already exited. */
  public long pid() {
    return process.pid();
  }

  /** Waits (blocking) for the program to exit and returns its code. */
  public int awaitExit() {
    try {
      return process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  public CompletableFuture<Integer> exitFuture() {
    return exit;
  }

  /**
   * Sends a control command ({@code debug on}, {@code screenshot <path>},
   * {@code gif start <path>}, {@code gif stop}, {@code pause}, {@code resume},
   * {@code step}, {@code speed <fps>}, {@code monitor on|off},
   * {@code pin <id> <field> <label>}, {@code unpin <id> <field>}); needs a run with
   * {@link RunConfig#withControl()}. Returns false when the program is gone.
   */
  public synchronized boolean send(String command) {
    if (!process.isAlive()) {
      return false;
    }
    try {
      var in = process.getOutputStream();
      in.write((command + "\n").getBytes(StandardCharsets.UTF_8));
      in.flush();
      return true;
    } catch (java.io.IOException e) {
      return false;
    }
  }

  /**
   * Stops the whole process tree: descendants first (JOGL/Processing native
   * threads must not keep the JVM alive), then the process itself, escalating
   * to a hard kill if it ignores the polite termination.
   */
  public void stop() {
    if (!process.isAlive()) {
      return;
    }
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroy();
    try {
      if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Lines from the launcher's control channel (hidden from the console). */
  static final String CONTROL_MARK = "@@scratch4j-ide@@ ";

  private static void control(String message, RunListener listener) {
    if (message.startsWith("frames ")) {
      try {
        String[] beat = message.substring("frames ".length()).trim().split(" ");
        listener.onFrames(Long.parseLong(beat[0]),
            beat.length > 1 && beat[1].equals("loading"));
      } catch (NumberFormatException ignored) {
        // a garbled heartbeat is skipped
      }
    } else if (message.startsWith("monitor ")) {
      try {
        listener.onState(ProgramState.parse(message.substring("monitor ".length())));
      } catch (RuntimeException ignored) {
        // a garbled report is skipped; the next one comes soon
      }
    } else if (message.startsWith("paused ")) {
      try {
        listener.onPaused(Long.parseLong(message.substring("paused ".length()).trim()));
      } catch (NumberFormatException ignored) {
        // garbled
      }
    } else if (message.equals("resumed")) {
      listener.onResumed();
    } else if (message.startsWith("saved ")) {
      listener.onSaved(message.substring("saved ".length()));
    } else if (message.startsWith("error ")) {
      listener.onStderr(message.substring("error ".length()));
    }
  }

  static void pump(Process process, RunListener listener, CompletableFuture<Integer> exit) {
    Thread stdout = new Thread(() -> pumpStream(process, true, listener), "scratch4j-run-stdout");
    Thread stderr = new Thread(() -> pumpStream(process, false, listener), "scratch4j-run-stderr");
    stdout.setDaemon(true);
    stderr.setDaemon(true);
    stdout.start();
    stderr.start();
    process.onExit().thenAccept(p -> exit.complete(p.exitValue()));
  }

  private static void pumpStream(Process process, boolean isStdout, RunListener listener) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        isStdout ? process.getInputStream() : process.getErrorStream(),
        StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!isStdout && line.startsWith(CONTROL_MARK)) {
          control(line.substring(CONTROL_MARK.length()), listener);
          continue;
        }
        if (isStdout) {
          listener.onStdout(line);
        } else {
          listener.onStderr(line);
        }
      }
    } catch (Exception ignored) {
      // stream ends when the process dies; a killed stream is normal on stop()
    }
  }
}
