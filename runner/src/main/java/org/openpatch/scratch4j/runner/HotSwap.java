package org.openpatch.scratch4j.runner;

import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Changes while the program runs: the project is compiled again (into its own
 * folder), and every class whose bytecode changed is swapped into the running
 * program through its debug connection. Method bodies change at once; new
 * fields or methods need a restart. Objects already exist, so a changed
 * constructor or attribute starting value only shows after a restart: such
 * classes are reported (once per run) in {@link Result#setupChanged()}.
 */
public final class HotSwap {

  /** What happened: the swapped classes, or why not (compile errors / restart needed). */
  public record Result(Status status, List<String> classes, String detail,
      CompileResult compile, List<String> setupChanged, List<ValueUpdate> values) {

    Result(Status status, List<String> classes, String detail, CompileResult compile) {
      this(status, classes, detail, compile, List.of(), List.of());
    }
  }

  /** A starting value given to running objects: {@code Player.speed = 8} in 3 objects. */
  public record ValueUpdate(String className, String field, String value, int objects) {}

  public enum Status { SWAPPED, NOTHING_CHANGED, COMPILE_ERRORS, RESTART_NEEDED }

  private final ScratchProject project;
  private final Debugger session;
  /** The setup code the running program's objects were made with. */
  private final Map<String, String> started;
  private final java.util.Set<String> warned = new java.util.HashSet<>();

  /** Remembers the code as it is now: the program was started from it. */
  public HotSwap(ScratchProject project, Debugger session) throws IOException {
    this.project = project;
    this.session = session;
    this.started = setupCode(project);
    this.values = startValues(project);
  }

  /** The literal starting values the running objects have now (updated by each apply). */
  private Map<String, org.openpatch.scratch4j.core.compile.SetupCode.StartValue> values;

  private static Map<String, org.openpatch.scratch4j.core.compile.SetupCode.StartValue>
      startValues(ScratchProject project) throws IOException {
    Map<String, org.openpatch.scratch4j.core.compile.SetupCode.StartValue> out =
        new java.util.HashMap<>();
    for (Path source : project.javaSources()) {
      out.putAll(org.openpatch.scratch4j.core.compile.SetupCode.startValues(
          Files.readString(source, java.nio.charset.StandardCharsets.UTF_8)));
    }
    return out;
  }

  private static Map<String, String> setupCode(ScratchProject project) throws IOException {
    Map<String, String> out = new java.util.HashMap<>();
    for (Path source : project.javaSources()) {
      out.putAll(org.openpatch.scratch4j.core.compile.SetupCode.of(
          Files.readString(source, java.nio.charset.StandardCharsets.UTF_8)));
    }
    return out;
  }

  public synchronized Result apply() throws IOException {
    Result result = apply(project, session);
    if (result.status() != Status.SWAPPED) {
      return result;
    }
    List<String> setup = new ArrayList<>();
    for (String name : org.openpatch.scratch4j.core.compile.SetupCode.changed(started,
        setupCode(project))) {
      if (warned.add(name)) setup.add(name);
    }
    // changed literal starting values go into the objects that exist already
    List<ValueUpdate> updates = new ArrayList<>();
    var now = startValues(project);
    for (var entry : now.entrySet()) {
      var before = values.get(entry.getKey());
      var after = entry.getValue();
      // a new attribute (enhanced hot reload) of a class that existed: its starting value
      boolean added = before == null && started.containsKey(after.className())
          && !values.containsKey(entry.getKey());
      if (!added && (before == null || before.literal().equals(after.literal())
          || !before.type().equals(after.type()) || before.isStatic() != after.isStatic())) {
        continue;
      }
      int count;
      try {
        count = session.updateStartValue(after.className(), after.field(),
            added ? null : before.literal(), after.literal(), after.isStatic());
      } catch (RuntimeException e) {
        count = 0;
      }
      updates.add(new ValueUpdate(after.className(), after.field(), after.literal(), count));
    }
    values = now;
    return new Result(result.status(), result.classes(), result.detail(), result.compile(),
        setup, updates);
  }

  public static Result apply(ScratchProject project, Debugger session) throws IOException {
    Path build = project.root().resolve(".scratch4j/build");
    Path running = build.resolve("classes");
    Path fresh = build.resolve("hotswap");
    deleteTree(fresh);
    List<Path> classpath = new ArrayList<>(project.libs());
    classpath.add(project.root());
    CompileResult result = new CompilerService().compile(project.javaSources(), classpath,
        fresh);
    if (!result.success()) {
      return new Result(Status.COMPILE_ERRORS, List.of(), "", result);
    }
    Map<String, byte[]> changed = new LinkedHashMap<>();
    Map<Path, Path> copies = new LinkedHashMap<>();
    try (Stream<Path> walk = Files.walk(fresh)) {
      for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
        Path relative = fresh.relativize(file);
        Path old = running.resolve(relative);
        byte[] bytes = Files.readAllBytes(file);
        if (Files.isRegularFile(old) && Arrays.equals(bytes, Files.readAllBytes(old))) {
          continue;
        }
        String name = relative.toString().replace('\\', '/').replaceFirst("\\.class$", "")
            .replace('/', '.');
        changed.put(name, bytes);
        copies.put(file, old);
      }
    }
    if (changed.isEmpty()) {
      return new Result(Status.NOTHING_CHANGED, List.of(), "", result);
    }
    List<String> swapped;
    try {
      swapped = session.redefine(changed);
    } catch (UnsupportedOperationException e) {
      return new Result(Status.RESTART_NEEDED, List.copyOf(changed.keySet()),
          String.valueOf(e.getMessage()), result);
    } catch (RuntimeException | LinkageError e) {
      // VerifyError, ClassFormatError, a disconnected program
      return new Result(Status.RESTART_NEEDED, List.copyOf(changed.keySet()),
          e.getClass().getSimpleName() + ": " + e.getMessage(), result);
    }
    // classes not loaded yet load the new version from disk later
    for (var copy : copies.entrySet()) {
      Files.createDirectories(copy.getValue().getParent());
      Files.copy(copy.getKey(), copy.getValue(), StandardCopyOption.REPLACE_EXISTING);
    }
    return new Result(Status.SWAPPED, swapped, "", result);
  }

  private static void deleteTree(Path dir) throws IOException {
    if (!Files.exists(dir)) return;
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
