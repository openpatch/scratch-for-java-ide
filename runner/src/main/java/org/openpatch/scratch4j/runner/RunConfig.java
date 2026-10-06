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
   * JOGL loads its native libraries via {@code System.load}; on JDK 25 that
   * prints four "restricted method" warnings to stderr (shown in red to
   * students) unless native access is enabled for the classpath.
   */
  public static final List<String> DEFAULT_JVM_ARGS = List.of("--enable-native-access=ALL-UNNAMED");

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
