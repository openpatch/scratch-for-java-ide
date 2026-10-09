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

  /**
   * The heartbeat with whether the library still shows its loading screen
   * (where a slow PC may draw nothing for a while). Defaults to {@link #onFrames(long)}.
   */
  default void onFrames(long frames, boolean loading) {
    onFrames(frames);
  }

  /** The game loop stopped ({@code pause}, or after a {@code step}) at this frame. */
  default void onPaused(long frame) {}

  /** The game loop runs again after {@code resume}. */
  default void onResumed() {}

  /** The program's variables, a few times a second and whenever it pauses. */
  default void onState(ProgramState state) {}

  /** The program saved a file the IDE asked for (screenshot, GIF). */
  default void onSaved(String path) {}

  /** The program exited with the given code. */
  default void onExit(int code) {}

  /** The runner could not start the JVM at all. */
  default void onError(String message) {}
}
