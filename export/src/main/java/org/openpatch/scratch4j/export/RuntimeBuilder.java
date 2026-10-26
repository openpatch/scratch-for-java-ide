package org.openpatch.scratch4j.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Java runtime with the in-process jlink ({@code jdk.jlink} tool
 * provider): given a jmods directory — the current JDK's own for native
 * exports, a downloaded target-JDK's for cross-OS exports — it links the
 * modules the student program needs.
 */
public final class RuntimeBuilder {

  private RuntimeBuilder() {}

  /**
   * Fallback for JDKs without jmods (Temurin 25 ships none - verified: the
   * 25.0.4.1 JDK archive contains zero jmods entries, JEP 493): bundle the
   * running JDK's own runtime as the app runtime. Bigger than a trimmed
   * image, but it always works.
   */
  public static void copyRuntime(Path outDir) throws IOException {
    copyRuntime(Path.of(System.getProperty("java.home")), outDir);
    Path javaBin = outDir.resolve("bin").resolve(
        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
    if (!Files.isRegularFile(javaBin)) {
      throw new IOException("Copied runtime has no java binary at " + javaBin);
    }
  }

  /** Copies the runtime at {@code javaHome} (this JDK's, or a downloaded target JRE). */
  public static void copyRuntime(Path javaHome, Path outDir) throws IOException {
    try (var files = Files.walk(javaHome)) {
      for (Path file : files.toList()) {
        Path target = outDir.resolve(javaHome.relativize(file).toString());
        if (Files.isDirectory(file)) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
              java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  /** Whether this runtime has a jlink binary (an installed IDE's runtime has none). */
  public static boolean canLink() {
    return Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").toLowerCase().contains("win") ? "jlink.exe" : "jlink"));
  }

  /** The jmods dir of the JDK the IDE itself runs on, when it ships jmods. */
  public static Path currentJmods() {
    Path jmods = Path.of(System.getProperty("java.home"), "jmods");
    return Files.isDirectory(jmods) ? jmods : null;
  }

  /**
   * Links a runtime from {@code jmodsDir} with the given modules into
   * {@code outDir}. {@code java.base} is always included.
   */
  public static void link(Path jmodsDir, Path outDir, String... modules)
      throws IOException {
    if (!Files.isDirectory(jmodsDir)) {
      throw new IOException("Not a jmods directory: " + jmodsDir);
    }
    Files.createDirectories(outDir.getParent() == null ? outDir : outDir.getParent());
    List<String> args = new ArrayList<>();
    args.add("--output");
    args.add(outDir.toString());
    args.add("--add-modules");
    String joined = String.join(",", modules);
    if (joined.isBlank() || !joined.contains("java.base")) {
      joined = joined.isBlank() ? "java.base" : "java.base," + joined;
    }
    args.add(joined);
    args.add("--strip-debug");
    args.add("--no-header-files");
    args.add("--no-man-pages");
    args.add("--compress=zip-6");
    args.add("--module-path");
    args.add(jmodsDir.toString());

    // the jlink binary ships with every JDK; --add-modules covers the rest
    Path jlink = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").toLowerCase().contains("win") ? "jlink.exe" : "jlink");
    if (!Files.isRegularFile(jlink)) {
      throw new IOException("No jlink in this JDK: " + jlink);
    }
    ProcessBuilder builder = new ProcessBuilder(jlink.toString());
    builder.command().addAll(args);
    builder.redirectErrorStream(true);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new IOException("Could not start jlink", e);
    }
    String output = new String(process.getInputStream().readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    int code;
    try {
      code = process.waitFor();
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("jlink interrupted", e);
    }
    if (code != 0 || !Files.exists(outDir.resolve("bin"))) {
      throw new IOException("jlink failed (code " + code + "):\n" + output);
    }
  }

  /**
   * What a Scratch for Java 7 program runs on: the library uses no {@code java.desktop} (no AWT,
   * no ImageIO, no Swing); Jackson (save and tiled) reads XML documents ({@code java.xml}); built-in
   * sounds are found inside the jar ({@code jdk.zipfs}); LWJGL uses {@code jdk.unsupported}.
   * Checked 2026-10-22 on such a runtime: JSON saving, a Tiled map, built-in sounds, shaders.
   */
  static final List<String> FLOOR = List.of("java.base", "java.xml", "jdk.unsupported", "jdk.zipfs");

  /** What jdeps names for the library jar that Jackson only uses when present (java.beans, java.sql types). */
  static final java.util.Set<String> OPTIONAL = java.util.Set.of("java.desktop", "java.sql",
      "java.datatransfer");

  /** The modules for the library alone (an export adds the student's own classes, see below). */
  public static List<String> modulesFor(Path libraryJar) throws IOException {
    return modulesFor(libraryJar, null);
  }

  /**
   * The JDK modules an exported program needs: the floor, what jdeps reports for the library jar
   * minus Jackson's optional modules, and everything the student's own classes use (a student who
   * opens a Swing dialog gets {@code java.desktop} back). Exported games stay small (plan.md Phase 7).
   */
  public static List<String> modulesFor(Path libraryJar, Path studentClasses) throws IOException {
    List<String> modules = new ArrayList<>(FLOOR);
    for (String m : jdeps(libraryJar)) {
      if (!OPTIONAL.contains(m) && !modules.contains(m)) {
        modules.add(m);
      }
    }
    if (studentClasses != null) {
      for (String m : jdeps(studentClasses)) {
        if (!modules.contains(m)) {
          modules.add(m);
        }
      }
    }
    return modules;
  }

  /** {@code jdeps --print-module-deps} of a jar or folder; empty when there is none or jdeps fails. */
  private static List<String> jdeps(Path path) throws IOException {
    if (path == null || !Files.exists(path)) {
      return List.of();
    }
    Path jdeps = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").toLowerCase().contains("win") ? "jdeps.exe" : "jdeps");
    if (!Files.isRegularFile(jdeps)) {
      return List.of();
    }
    try {
      ProcessBuilder builder = new ProcessBuilder(jdeps.toString(),
          "--print-module-deps", "--ignore-missing-deps", "--multi-release", "base",
          path.toString());
      builder.redirectErrorStream(false);
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8).trim();
      process.waitFor();
      if (output.isBlank() || !output.contains("java.base")) {
        return List.of();
      }
      return List.of(output.split(","));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    }
  }
}
