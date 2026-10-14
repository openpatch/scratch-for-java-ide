package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends standard window-manager requests without optimistically changing JavaFX state.
 * Missing tools, ignored requests and timeouts are no-ops. Never runs on the FX thread.
 */
final class X11WindowActions {

  enum Action { MINIMIZE, MAXIMIZE, RESTORE }

  @FunctionalInterface
  interface Command {
    String run(String... arguments) throws IOException, InterruptedException;
  }

  private final Command command;
  private final AtomicBoolean busy = new AtomicBoolean();

  X11WindowActions() {
    this(X11WindowActions::runCommand);
  }

  X11WindowActions(Command command) {
    this.command = command;
  }

  CompletableFuture<Void> request(String title, Action action) {
    if (!busy.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
    CompletableFuture<Void> done = new CompletableFuture<>();
    Thread.ofPlatform().daemon(true).name("window-action").start(() -> {
      try {
        send(title, action);
      } catch (IOException ignored) {
        // No native helper or display: leave the window and the FX state alone.
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        busy.set(false);
        done.complete(null);
      }
    });
    return done;
  }

  private void send(String title, Action action) throws IOException, InterruptedException {
    String windows = command.run("xdotool", "search", "--onlyvisible", "--pid",
        Long.toString(ProcessHandle.current().pid()));
    String target = null;
    // Match our process AND the stage title; never act on whichever window has focus.
    for (String id : windows.lines().limit(16).toList()) {
      if (!id.matches("[0-9]+")) continue;
      if (title.equals(command.run("xdotool", "getwindowname", id))) {
        if (target != null) return; // ambiguous: doing nothing is safer
        target = id;
      }
    }
    if (target == null) return;
    if (action == Action.MINIMIZE) {
      command.run("xdotool", "windowminimize", target);
    } else {
      String change = action == Action.MAXIMIZE ? "--add" : "--remove";
      command.run("xdotool", "windowstate", change, "MAXIMIZED_VERT", change,
          "MAXIMIZED_HORZ", target);
    }
  }

  static String runCommand(String... arguments) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(arguments)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try {
      if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
      return new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8)
          .stripTrailing();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
      process.getInputStream().close();
    }
  }
}
