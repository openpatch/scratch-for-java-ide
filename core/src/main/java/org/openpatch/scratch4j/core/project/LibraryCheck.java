package org.openpatch.scratch4j.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What library a project carries in {@code +libs} compared with the one the
 * IDE ships, and the consented swap: the jar is replaced only when the user
 * agrees (old jars go to {@code .scratch4j/trash}); declining pins the
 * project's version in {@code .scratch4j/project.json} so the IDE stops
 * asking. Also the NRW flavour's Abiturklassen check.
 */
public final class LibraryCheck {

  public enum State {
    /** No Scratch for Java jar at all: the project cannot compile. */
    MISSING,
    /** An older version than the IDE's. */
    OUTDATED,
    /** A newer version than the IDE's (left alone). */
    NEWER,
    /** The right version, but the other flavour (standard vs NRW). */
    OTHER_FLAVOUR,
    /** The IDE's own version and flavour. */
    CURRENT,
    /** A jar whose version cannot be read (e.g. renamed). */
    UNKNOWN
  }

  public record Status(State state, Path jar, String version, LibraryFlavour flavour) {

    /** Whether the IDE should offer to put its own jar in (and the user was not asked before). */
    public boolean offerSwap(ProjectSettings settings) {
      if (state == State.MISSING) {
        return true;
      }
      if (state != State.OUTDATED && state != State.OTHER_FLAVOUR) {
        return false;
      }
      return settings.libraryPin == null || !settings.libraryPin.equals(version + flavourTag());
    }

    private String flavourTag() {
      return flavour == LibraryFlavour.NRW ? "-nrw" : "";
    }
  }

  private static final Pattern JAR =
      Pattern.compile("scratch-(\\d+(?:\\.\\d+){1,2})(-nrw)?(?:-all)?\\.jar");

  private LibraryCheck() {}

  /** Compares {@code +libs} with the IDE's {@code version} in the project's chosen flavour. */
  public static Status status(ScratchProject project, String version) throws IOException {
    LibraryFlavour wanted = LibraryFlavour.fromId(project.settings().flavour);
    Status best = new Status(State.MISSING, null, null, null);
    for (Path jar : project.libs()) {
      Matcher m = JAR.matcher(jar.getFileName().toString());
      if (!m.matches()) {
        if (jar.getFileName().toString().startsWith("scratch")) {
          best = new Status(State.UNKNOWN, jar, null, null);
        }
        continue;
      }
      LibraryFlavour flavour = m.group(2) != null ? LibraryFlavour.NRW : LibraryFlavour.STANDARD;
      int cmp = compare(m.group(1), version);
      State state = cmp < 0 ? State.OUTDATED : cmp > 0 ? State.NEWER
          : flavour != wanted ? State.OTHER_FLAVOUR : State.CURRENT;
      return new Status(state, jar, m.group(1), flavour);
    }
    return best;
  }

  /** Remembers that the user keeps this jar: no more swap offers for it. */
  public static void pin(ScratchProject project, Status status) throws IOException {
    project.settings().libraryPin = status.version()
        + (status.flavour() == LibraryFlavour.NRW ? "-nrw" : "");
    project.settings().save(project.root());
  }

  /**
   * Puts {@code bundledJar} into {@code +libs}; every other Scratch for Java
   * jar there moves to the trash (recoverable). Clears a pin.
   */
  public static Path install(ScratchProject project, Path bundledJar) throws IOException {
    Path libs = project.libsDir();
    Files.createDirectories(libs);
    Path trash = project.root().resolve(".scratch4j/trash");
    for (Path jar : project.libs()) {
      String name = jar.getFileName().toString();
      if (name.startsWith("scratch") && !name.equals(bundledJar.getFileName().toString())) {
        Files.createDirectories(trash);
        Files.move(jar, trash.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID()
            + "-" + name));
      }
    }
    Path target = libs.resolve(bundledJar.getFileName());
    Files.copy(bundledJar, target, StandardCopyOption.REPLACE_EXISTING);
    project.settings().libraryPin = "";
    project.settings().save(project.root());
    return target;
  }

  /** NRW projects need the Abiturklassen {@code List.java} (the NRW jar's API returns it). */
  public static boolean nrwListMissing(ScratchProject project) throws IOException {
    if (LibraryFlavour.fromId(project.settings().flavour) != LibraryFlavour.NRW) {
      return false;
    }
    return project.javaSources().stream()
        .noneMatch(file -> file.getFileName().toString().equals("List.java"));
  }

  /** The library version in the project's {@code +libs} ({@code 5.6.0}), or null. */
  public static String projectVersion(ScratchProject project) {
    try {
      return status(project, "0").version();
    } catch (IOException e) {
      return null;
    }
  }

  /** Whether {@code candidate} is a newer version than {@code current}. */
  public static boolean isNewer(String candidate, String current) {
    return compare(candidate, current) > 0;
  }

  /** Numeric dotted-version comparison (5.10.0 is newer than 5.9.2). */
  static int compare(String a, String b) {
    String[] x = a.split("\\.");
    String[] y = b.split("\\.");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      int left = i < x.length ? Integer.parseInt(x[i]) : 0;
      int right = i < y.length ? Integer.parseInt(y[i]) : 0;
      if (left != right) {
        return Integer.compare(left, right);
      }
    }
    return 0;
  }
}
