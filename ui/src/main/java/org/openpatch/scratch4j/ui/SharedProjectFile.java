package org.openpatch.scratch4j.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A project someone handed over as one file: a project ZIP or an Online-IDE
 * workspace JSON. Project > Import project (ZIP / workspace JSON) imports it, and so
 * does starting Studio with the file as its first argument.
 */
final class SharedProjectFile {

  private SharedProjectFile() {}

  /** True for an existing {@code .zip} or {@code .json} file. */
  static boolean accepts(Path file) {
    return Files.isRegularFile(file) && (isJson(file) || name(file).endsWith(".zip"));
  }

  /** True when {@code file} is a workspace JSON rather than a project ZIP. */
  static boolean isJson(Path file) {
    return name(file).endsWith(".json");
  }

  private static String name(Path file) {
    return file.getFileName().toString().toLowerCase(Locale.ROOT);
  }
}
