package org.openpatch.scratch4j.runner;

import java.nio.file.Path;
import java.util.List;

/** Configuration for one run. */
public record RunConfig(
    /** Stage class to start (simple name, resolved against the project's classes). */
    String stageClass,
    /** When positive, pass -Dscratch4j.ide.exitAfter=<seconds> to the program (CI smoke tests). */
    long exitAfterSeconds,
    /** The java executable to use; defaults to the current JVM's. */
    Path javaExecutable,
    /** Extra JVM flags. */
    List<String> extraJvmArgs,
    /** The IDE's control channel: commands via stdin, frame heartbeats via stderr. */
    boolean control,
    /** A JDWP agent flag from {@link Debugger#jvmArgument()}, or null. */
    String debugAgent) {

  /**
   * LWJGL loads its native libraries via {@code System.load}; on JDK 25 that
   * prints "restricted method" warnings to stderr (shown in red to students)
   * unless native access is enabled for the classpath.
   */
  public static final List<String> DEFAULT_JVM_ARGS = List.of("--enable-native-access=ALL-UNNAMED");

  /** The library's launcher: it keeps the first thread for the window (macOS needs that). */
  public static final String LAUNCHER = "org.openpatch.scratch.Launcher";

  /**
   * What starts a program, after the JVM options and the classpath: the library's launcher,
   * then the generated Scratch4JLauncher, then the start class. On macOS GLFW must own the
   * process's first thread, so Java starts with {@code -XstartOnFirstThread} (returned first,
   * before the main class).
   */
  public static List<String> launch(String startClass) {
    List<String> out = new java.util.ArrayList<>();
    if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
      out.add("-XstartOnFirstThread");
    }
    out.add(LAUNCHER);
    out.add("Scratch4JLauncher");
    out.add(startClass);
    return out;
  }

  public static RunConfig of(String stageClass) {
    return new RunConfig(stageClass, -1,
        Path.of(System.getProperty("java.home"), "bin", "java"), DEFAULT_JVM_ARGS, false, null);
  }

  public RunConfig withExitAfter(long seconds) {
    return new RunConfig(stageClass, seconds, javaExecutable, extraJvmArgs, control, debugAgent);
  }

  /** Debug toggle, screenshots, GIF recording and the frozen-program watchdog. */
  /** Runs the program on another Java (e.g. one with enhanced hot reload). */
  public RunConfig withJava(Path java) {
    return new RunConfig(stageClass, exitAfterSeconds, java, extraJvmArgs, control, debugAgent);
  }

  public RunConfig withControl() {
    return new RunConfig(stageClass, exitAfterSeconds, javaExecutable, extraJvmArgs, true,
        debugAgent);
  }

  /** Runs under the debugger: the program connects to it and waits for breakpoints. */
  public RunConfig withDebugger(Debugger debugger) {
    return new RunConfig(stageClass, exitAfterSeconds, javaExecutable, extraJvmArgs, control,
        debugger.jvmArgument());
  }
}
