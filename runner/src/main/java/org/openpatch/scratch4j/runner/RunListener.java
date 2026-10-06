package org.openpatch.scratch4j.runner;

/** Callbacks for one run of a student program. All callbacks come from the runner's background threads. */
public interface RunListener {

  /** A line (without newline) from the program's stdout. */
  default void onStdout(String line) {}

  /** A line (without newline) from the program's stderr. */
  default void onStderr(String line) {}

  /** Compilation finished with errors before anything was started. */
  default void onCompileFailed(org.openpatch.scratch4j.core.compile.CompileResult result) {}

  /**
   * A heartbeat from the control channel: frames drawn so far (-1 while the
   * window does not exist yet). About once a second.
   */
  default void onFrames(long frames) {}

  /** The program saved a file the IDE asked for (screenshot, GIF). */
  default void onSaved(String path) {}

  /** The program exited with the given code. */
  default void onExit(int code) {}

  /** The runner could not start the JVM at all. */
  default void onError(String message) {}
}
